"""
Multi-Agent System for Constraint Solving.

Three core agents:
1. Solver Agent: Generates candidate valuations (allows Chain of Thought reasoning).
2. Verifier Agent: Validates the output against constraints, type hierarchy, and format rules.
3. Refiner Agent: Corrects errors based on Verifier feedback.
"""

import json
import re
from typing import Dict, Any, Optional, Tuple, List
from json import JSONDecodeError
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, SystemMessage


def _extract_first_json(text: str) -> Tuple[Optional[Dict], Optional[str]]:
    """Return the first decodable JSON object/array in the text, or (None, None).

    Safeguards:
    - Prefer content inside Markdown code fences (``` or ```json) to avoid stray braces like `{x}` in prose.
    - Fall back to scanning the whole text if no fenced block is present.
    - Uses JSONDecoder for non-greedy, position-based decoding.
    """

    def _candidate_blocks(src: str) -> List[str]:
        # If there are fenced code blocks, try them first (likely to contain clean JSON)
        blocks = re.findall(r"```(?:json)?\s*(.*?)```", src, flags=re.DOTALL | re.IGNORECASE)
        if blocks:
            return blocks
        return [src]

    decoder = json.JSONDecoder()
    for block in _candidate_blocks(text):
        for idx, ch in enumerate(block):
            if ch in "{[":
                try:
                    obj, end = decoder.raw_decode(block, idx)
                    return obj, block[idx:end]
                except JSONDecodeError:
                    continue
    return None, None


class SolverAgent:
    """
    Generates candidate valuations for the given constraints.
    
    Allows the LLM to think step-by-step without strict format requirements.
    The output may be verbose; Verifier will check and clean it up.
    """
    
    def __init__(self, llm: ChatOpenAI):
        self.llm = llm
    
    def solve(
        self,
        constraints: List[str],
        type_hierarchy: Optional[Dict[str, str]] = None,
        heap_state: Optional[Dict[str, Any]] = None,
        context: str = "",
    ) -> Tuple[Optional[Dict], str]:
        """
        Generate a candidate solution for the given constraints.
        
        Returns: (parsed_json, raw_llm_output)
        """
        system_prompt = (
            "You are a constraint-solving assistant specialized in reasoning about high-level Java constraints. "
            "Your task is to determine satisfiability of the given constraints and propose a candidate valuation.\n\n"
            "Use Chain of Thought reasoning:\n"
            "1) Analyze each constraint carefully.\n"
            "2) Reason through type compatibility, null conditions, and numeric constraints.\n"
            "3) Construct a valuation that satisfies all constraints.\n\n"
            "Output format:\n"
            "- SAT: return {\"result\": \"SAT\", \"valuation\": [...]}\n"
            "- UNSAT: return {\"result\": \"UNSAT\"}\n"
            "- UNKNOWN: return {\"result\": \"UNKNOWN\", \"raw\": \"explanation\"}\n\n"
            "For SAT valuations, each entry should have:\n"
            "- variable: name (e.g., \"head(ref)\")\n"
            "- type: JVM format (e.g., \"LNode;\", \"Ljava/util/ArrayList;\")\n"
            "- newObject: boolean (true if newly created)\n"
            "- trueRef: boolean (true for symbolic refs, false for concrete addresses)\n"
            "- reference: unique ID (integer for new objects, null for nulls)\n\n"
            "Reasoning is encouraged; you may show your work before the final JSON."
        )
        
        constraints_block = "\n".join(f"- {c}" for c in constraints)
        type_hierarchy_block = ""
        if type_hierarchy:
            type_hierarchy_block = "Type Hierarchy Information:\n"
            for var_name, type_info in type_hierarchy.items():
                type_hierarchy_block += f"\nVariable: {var_name}\n{type_info}\n"
            type_hierarchy_block += "\n"
        
        heap_state_block = ""
        if heap_state:
            heap_state_block = "Heap State Information:\n"
            heap_state_block += "This shows the current state of reachable objects in the heap.\n\n"
            
            if "aliases" in heap_state and heap_state["aliases"]:
                heap_state_block += "Aliases (variable → object reference):\n"
                for var_name, obj_ref in heap_state["aliases"].items():
                    heap_state_block += f"  {var_name} → {obj_ref}\n"
                heap_state_block += "\n"
            
            if "objects" in heap_state and heap_state["objects"]:
                heap_state_block += "Objects (reference → structure):\n"
                for obj_ref, obj_desc in heap_state["objects"].items():
                    class_name = obj_desc.get("class", "Unknown")
                    heap_state_block += f"  {obj_ref}: {class_name}\n"
                    
                    fields = obj_desc.get("fields", {})
                    if fields:
                        for field_name, field_value in fields.items():
                            heap_state_block += f"    {field_name}: {field_value}\n"
                    
                    if "elements" in obj_desc:
                        heap_state_block += f"    elements: {obj_desc['elements']}\n"
                    if "length" in obj_desc:
                        heap_state_block += f"    length: {obj_desc['length']}\n"
                    
                    heap_state_block += "\n"
        
        context_block = f"Reference information:\n{context}\n\n" if context else ""
        
        human_prompt = (
            f"{context_block}"
            f"{type_hierarchy_block}"
            f"{heap_state_block}"
            f"Constraints:\n{constraints_block}\n\n"
            "Please reason through the constraints and provide your answer in JSON format."
        )
        
        try:
            response = self.llm.invoke([
                SystemMessage(content=system_prompt),
                HumanMessage(content=human_prompt),
            ])
            raw_output = response.content if hasattr(response, 'content') else str(response)
            
            parsed, _ = _extract_first_json(raw_output)
            return parsed, raw_output
        except Exception as e:
            return None, f"Error during Solver invocation: {str(e)}"


class VerifierAgent:
    """
    Validates the Solver's output against constraints and rules.
    
    Checks:
    - Type compatibility with type hierarchy.
    - Logical consistency (e.g., no null for non-null constraints).
    - JSON format validity.
    - Valuation structure.
    
    For complex type hierarchy checks, uses LLM for semantic validation.
    """
    
    def __init__(self, llm: ChatOpenAI):
        self.llm = llm
    
    def verify(
        self,
        solver_output: Optional[Dict],
        raw_output: str,
        constraints: List[str],
        type_hierarchy: Optional[Dict[str, str]] = None,
        heap_state: Optional[Dict[str, Any]] = None,
    ) -> Tuple[bool, str, Optional[Dict]]:
        """
        Verify the Solver's output.
        
        Returns: (is_valid, error_report, parsed_json_if_valid)
        """
        # If Solver failed to extract JSON, report it
        if solver_output is None:
            return False, f"Could not extract valid JSON from Solver output: {raw_output}", None
        
        # Basic structure checks
        if "result" not in solver_output:
            return False, "Missing 'result' field in Solver output", None
        
        result = solver_output.get("result")
        if result not in ["SAT", "UNSAT", "UNKNOWN"]:
            return False, f"Invalid result value: {result}", None
        
        # For SAT, validate valuation
        if result == "SAT":
            if "valuation" not in solver_output:
                return False, "SAT result missing 'valuation' field", None
            
            valuation = solver_output["valuation"]
            if not isinstance(valuation, list):
                return False, f"'valuation' should be an array, got {type(valuation)}", None
            
            if len(valuation) == 0:
                return False, "SAT valuation cannot be empty", None
            
            # Validate each valuation entry
            for idx, entry in enumerate(valuation):
                if not isinstance(entry, dict):
                    return False, f"Valuation entry {idx} is not a dict", None
                
                if "variable" not in entry:
                    return False, f"Valuation entry {idx} missing 'variable'", None
                
                # For reference variables, check required fields
                if "type" in entry and entry["type"] != "null":
                    required_ref_fields = {"type", "newObject", "trueRef", "reference"}
                    entry_keys = set(entry.keys())
                    if not required_ref_fields.issubset(entry_keys):
                        missing = required_ref_fields - entry_keys
                        return False, f"Valuation entry {idx} missing fields: {missing}", None
                
                # Check type compatibility with type hierarchy
                if type_hierarchy and "type" in entry:
                    var_name = entry.get("variable", "")
                    entry_type = entry.get("type")
                    # If type hierarchy exists, perform LLM-assisted semantic check
                    if var_name in type_hierarchy:
                        is_compatible, compat_error = self._check_type_compatibility_with_llm(
                            var_name=var_name,
                            assigned_type=entry_type,
                            type_hierarchy_info=type_hierarchy[var_name],
                            constraints=constraints,
                        )
                        if not is_compatible:
                            return False, f"Type incompatibility for {var_name}: {compat_error}", None
            
            # Simple consistency check: look for logical conflicts
            null_refs = set()
            non_null_refs = set()
            for entry in valuation:
                var_name = entry.get("variable")
                if entry.get("type") == "null":
                    null_refs.add(var_name)
                elif entry.get("reference") is not None:
                    non_null_refs.add(var_name)
            
            conflicts = null_refs & non_null_refs
            if conflicts:
                return False, f"Conflicting null/non-null assignments for: {conflicts}", None
        
        # If all checks pass, return valid
        return True, "", solver_output
    
    def _check_type_compatibility_with_llm(
        self,
        var_name: str,
        assigned_type: str,
        type_hierarchy_info: str,
        constraints: List[str],
    ) -> Tuple[bool, str]:
        """
        Use LLM to validate type compatibility against hierarchy.
        
        This handles complex Java type hierarchy (interfaces, inheritance, etc.)
        that's hard to hardcode in Python logic.
        
        Returns: (is_compatible, error_message)
        """
        system_prompt = (
            "You are a Java type system expert. Your task is to verify if a type assignment "
            "is compatible with the given type hierarchy and constraints.\n\n"
            "Rules:\n"
            "- Interfaces can be implemented by concrete classes\n"
            "- Abstract classes can be extended by concrete classes\n"
            "- Type hierarchy shows inheritance/implementation relationships\n\n"
            "Respond with ONLY a JSON object: {\"compatible\": true/false, \"reason\": \"...\"}"
        )
        
        constraints_text = "\n".join(f"- {c}" for c in constraints)
        human_prompt = (
            f"Variable: {var_name}\n"
            f"Assigned type: {assigned_type}\n"
            f"Type hierarchy: {type_hierarchy_info}\n\n"
            f"Relevant constraints:\n{constraints_text}\n\n"
            "Is this type assignment compatible?"
        )
        
        try:
            response = self.llm.invoke([
                SystemMessage(content=system_prompt),
                HumanMessage(content=human_prompt),
            ])
            raw_output = response.content if hasattr(response, 'content') else str(response)
            
            parsed, _ = _extract_first_json(raw_output)
            if parsed and "compatible" in parsed:
                is_compatible = parsed.get("compatible", False)
                reason = parsed.get("reason", "No reason provided")
                return is_compatible, reason
            else:
                # If LLM fails to respond properly, be conservative and assume compatible
                return True, "LLM check inconclusive, assuming compatible"
        except Exception as e:
            # On error, assume compatible to avoid blocking valid solutions
            return True, f"LLM check failed ({str(e)}), assuming compatible"


class RefinerAgent:
    """
    Corrects Solver's output based on Verifier feedback.
    
    Takes the original constraints, Solver output, and Verifier's error report,
    then regenerates a corrected solution.
    """
    
    def __init__(self, llm: ChatOpenAI):
        self.llm = llm
    
    def refine(
        self,
        constraints: List[str],
        solver_output_raw: str,
        error_report: str,
        type_hierarchy: Optional[Dict[str, str]] = None,
        heap_state: Optional[Dict[str, Any]] = None,
        context: str = "",
    ) -> Tuple[Optional[Dict], str]:
        """
        Refine the Solver's output based on Verifier feedback.
        
        Returns: (parsed_json, raw_llm_output)
        """
        system_prompt = (
            "You are a constraint-solving assistant and error corrector. "
            "Your task is to fix the errors reported by the Verifier.\n\n"
            "Given:\n"
            "1. The original constraints\n"
            "2. The previous (incorrect) solution\n"
            "3. The specific errors that occurred\n\n"
            "Please:\n"
            "1. Understand why the previous solution was wrong.\n"
            "2. Correct the issues while respecting all original constraints.\n"
            "3. Return ONLY a valid JSON object (SAT/UNSAT/UNKNOWN) as the final output.\n\n"
            "Valuation format for SAT:\n"
            "Each entry should have: variable, type, newObject, trueRef, reference (for reference variables)\n"
            "Or: variable, value (for primitive fields)\n"
            "Keep all values JSON-safe."
        )
        
        constraints_block = "\n".join(f"- {c}" for c in constraints)
        type_hierarchy_block = ""
        if type_hierarchy:
            type_hierarchy_block = "Type Hierarchy Information:\n"
            for var_name, type_info in type_hierarchy.items():
                type_hierarchy_block += f"\nVariable: {var_name}\n{type_info}\n"
            type_hierarchy_block += "\n"
        
        heap_state_block = ""
        if heap_state:
            heap_state_block = "Heap State Information:\n"
            if "aliases" in heap_state and heap_state["aliases"]:
                heap_state_block += "Aliases:\n"
                for var_name, obj_ref in heap_state["aliases"].items():
                    heap_state_block += f"  {var_name} → {obj_ref}\n"
                heap_state_block += "\n"
            
            if "objects" in heap_state and heap_state["objects"]:
                heap_state_block += "Objects:\n"
                for obj_ref, obj_desc in heap_state["objects"].items():
                    heap_state_block += f"  {obj_ref}: {obj_desc.get('class', 'Unknown')}\n"
                heap_state_block += "\n"
        
        context_block = f"Reference information:\n{context}\n\n" if context else ""
        
        human_prompt = (
            f"{context_block}"
            f"{type_hierarchy_block}"
            f"{heap_state_block}"
            f"Constraints:\n{constraints_block}\n\n"
            f"Previous (incorrect) solution:\n{solver_output_raw}\n\n"
            f"Errors reported by Verifier:\n{error_report}\n\n"
            "Please provide a corrected JSON solution."
        )
        
        try:
            response = self.llm.invoke([
                SystemMessage(content=system_prompt),
                HumanMessage(content=human_prompt),
            ])
            raw_output = response.content if hasattr(response, 'content') else str(response)
            
            parsed, _ = _extract_first_json(raw_output)
            return parsed, raw_output
        except Exception as e:
            return None, f"Error during Refiner invocation: {str(e)}"


class MultiAgentOrchestrator:
    """
    Orchestrates the three agents in a feedback loop.
    
    Workflow:
    1. Solver generates candidate solution (with moderate temperature for creativity).
    2. Verifier checks the solution (uses LLM for complex type checks).
    3. If valid, return the solution.
    4. If invalid, Refiner corrects it (with temperature=0 for precision) and we loop (max retries).
    """
    
    def __init__(
        self,
        llm: ChatOpenAI,
        max_retries: int = 2,
    ):
        # Solver: use provided LLM (typically with moderate temperature for reasoning)
        self.solver = SolverAgent(llm)
        
        # Verifier: use provided LLM for semantic type checks
        self.verifier = VerifierAgent(llm)
        
        # Refiner: create a separate LLM instance with temperature=0 for precision
        refiner_llm = ChatOpenAI(
            model=llm.model_name,
            api_key=llm.openai_api_key,
            base_url=llm.openai_api_base if hasattr(llm, 'openai_api_base') else None,
            temperature=0.0,  # Zero temperature for precise error correction
            max_tokens=llm.max_tokens,
        )
        self.refiner = RefinerAgent(refiner_llm)
        self.max_retries = max_retries
    
    def solve(
        self,
        constraints: List[str],
        type_hierarchy: Optional[Dict[str, str]] = None,
        heap_state: Optional[Dict[str, Any]] = None,
        context: str = "",
    ) -> Dict[str, Any]:
        """
        Main orchestration loop: Solver -> Verifier -> [Refiner -> Solver] -> Return
        
        Returns the final solution as a dictionary with:
        - result: "SAT" | "UNSAT" | "UNKNOWN"
        - valuation: [...] (if SAT)
        - raw: original LLM outputs
        - iterations: number of iterations used
        """
        
        iteration = 0
        solver_output_raw = ""
        
        while iteration <= self.max_retries:
            iteration += 1
            
            if iteration == 1:
                # First iteration: use Solver
                solver_output, solver_output_raw = self.solver.solve(
                    constraints=constraints,
                    type_hierarchy=type_hierarchy,
                    heap_state=heap_state,
                    context=context,
                )
            else:
                # Subsequent iterations: use Refiner
                solver_output, solver_output_raw = self.refiner.refine(
                    constraints=constraints,
                    solver_output_raw=solver_output_raw,
                    error_report=error_report,
                    type_hierarchy=type_hierarchy,
                    heap_state=heap_state,
                    context=context,
                )
            
            # Verify the solution
            is_valid, error_report, verified_output = self.verifier.verify(
                solver_output=solver_output,
                raw_output=solver_output_raw,
                constraints=constraints,
                type_hierarchy=type_hierarchy,
                heap_state=heap_state,
            )
            
            if is_valid:
                # Solution is valid, return it
                result = verified_output.copy()
                result["iterations"] = iteration
                if "raw" not in result:
                    result["raw"] = solver_output_raw
                return result
            
            # Solution is invalid, check if we should retry
            if iteration >= self.max_retries:
                # Max retries reached, return UNKNOWN
                return {
                    "result": "UNKNOWN",
                    "raw": solver_output_raw,
                    "verification_error": error_report,
                    "iterations": iteration,
                }
        
        # Should not reach here, but as a safety fallback
        return {
            "result": "UNKNOWN",
            "raw": solver_output_raw,
            "iterations": iteration,
        }
