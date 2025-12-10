"""
Refiner Agent - Corrects solver outputs based on verifier feedback.

This module contains the RefinerAgent class which takes incorrect solver outputs
and verifier error reports, then generates corrected solutions. Uses zero
temperature for deterministic, precise error correction.
"""

from typing import List, Dict, Optional, Any, Tuple
from langchain_openai import ChatOpenAI
from langchain_core.messages import SystemMessage, HumanMessage

from .utils import extract_first_json


class RefinerAgent:
    """
    Corrects Solver's output based on Verifier feedback.
    
    Takes the original constraints, Solver's incorrect output, and Verifier's
    error report, then regenerates a corrected solution. This agent should
    use temperature=0 for precise, deterministic corrections.
    
    Attributes:
        llm: ChatOpenAI instance (typically configured with temperature=0)
    """
    
    def __init__(self, llm: ChatOpenAI):
        """
        Initialize the RefinerAgent.
        
        Args:
            llm: ChatOpenAI instance, should use temperature=0 for precision
        """
        self.llm = llm
    
    def refine(
        self,
        constraints: List[str],
        solver_output_raw: str,
        error_report: str,
        type_hierarchy: Optional[Dict[str, str]] = None,
        heap_state: Optional[Dict[str, Any]] = None,
        parameter_type_constraints: Optional[Dict[str, str]] = None,
        context: str = "",
    ) -> Tuple[Optional[Dict], str, Dict[str, Any]]:
        """
        Refine the Solver's output based on Verifier feedback.
        
        Constructs a detailed prompt explaining:
        1. What the original constraints were
        2. What the previous (incorrect) solution was
        3. What specific errors the Verifier found
        
        The LLM is then asked to correct these errors while maintaining
        all original constraint satisfaction.
        
        Args:
            constraints: List of constraint strings to satisfy
            solver_output_raw: Raw string output from Solver that failed verification
            error_report: String describing what was wrong with the output
            type_hierarchy: Optional dict mapping variable names to type information
            heap_state: Optional dict with "aliases" and "objects" keys
            parameter_type_constraints: Optional dict mapping parameter names to their static types
            context: Optional reference information string
        
        Returns:
            Tuple of (parsed_json_dict, raw_llm_output_string, conversation_log)
            - parsed_json_dict is None if extraction fails
            - raw_llm_output_string contains the complete LLM response
            - conversation_log captures prompts/responses for logging
        
        Example:
            >>> refiner = RefinerAgent(llm)
            >>> result, raw = refiner.refine(
            ...     constraints=["head(ref) != null"],
            ...     solver_output_raw="...",
            ...     error_report="Missing 'newObject' field in valuation entry 0"
            ... )
            >>> result["result"]
            'SAT'
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
            "Keep all values JSON-safe.\n\n"
            "CRITICAL VARIABLE NAMING RULES:\n"
            "1. ONLY use variable names that appear in the constraints (e.g., 'head(ref)', 'head(ref).next(ref)')\n"
            "2. NEVER invent new variable names like 'obj#1', 'obj1', 'node1', 'temp', etc.\n"
            "3. For field access chains, use the exact dot notation from constraints\n"
            "4. Each unique variable from constraints gets ONE entry in the valuation\n\n"
            "EXAMPLES:\n"
            "✓ CORRECT: {\"variable\": \"head(ref)\", ...}\n"
            "✓ CORRECT: {\"variable\": \"head(ref).next(ref)\", ...}\n"
            "✗ WRONG: {\"variable\": \"obj#1\", ...}\n"
            "✗ WRONG: {\"variable\": \"node1\", ...}"
        )
        
        constraints_block = "\n".join(f"- {c}" for c in constraints)
        
        # Build parameter type constraints block (implicit constraints)
        param_type_block = ""
        if parameter_type_constraints:
            param_type_block = "Parameter Type Constraints (Implicit):\n"
            param_type_block += "These are the declared static types of method parameters. "
            param_type_block += "The actual runtime type must be a subtype of the declared type.\n\n"
            for param_name, declared_type in parameter_type_constraints.items():
                param_type_block += f"  {param_name}: declared type is {declared_type}\n"
            param_type_block += "\n"
            param_type_block += "When correcting the valuation, ensure type compatibility with declared types.\n\n"
        
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
            f"{param_type_block}"
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
            
            parsed, _ = extract_first_json(raw_output)
            log_entry = {
                "agent": "refiner",
                "stage": "refine",
                "system": system_prompt,
                "human": human_prompt,
                "response": raw_output,
            }
            return parsed, raw_output, log_entry
        except Exception as e:
            log_entry = {
                "agent": "refiner",
                "stage": "refine",
                "system": system_prompt,
                "human": human_prompt,
                "response": "",
                "error": str(e),
            }
            return None, f"Error during Refiner invocation: {str(e)}", log_entry
