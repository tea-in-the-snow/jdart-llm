"""
Solver Agent - Generates candidate valuations for constraint solving problems.

This module contains the SolverAgent class which uses LLM to reason through
constraints and propose candidate solutions. It employs Chain of Thought
reasoning and allows verbose output that will be validated by the Verifier.
"""

from typing import List, Dict, Optional, Any, Tuple
from langchain_openai import ChatOpenAI
from langchain_core.messages import SystemMessage, HumanMessage

from .utils import extract_first_json


class SolverAgent:
    """
    Generates candidate valuations for the given constraints.
    
    Allows the LLM to think step-by-step without strict format requirements.
    The output may be verbose; Verifier will check and clean it up.
    
    Attributes:
        llm: ChatOpenAI instance for LLM interactions
    """
    
    def __init__(self, llm: ChatOpenAI):
        """
        Initialize the SolverAgent.
        
        Args:
            llm: ChatOpenAI instance configured with desired temperature/model
        """
        self.llm = llm
    
    def solve(
        self,
        constraints: List[str],
        type_hierarchy: Optional[Dict[str, str]] = None,
        heap_state: Optional[Dict[str, Any]] = None,
        context: str = "",
    ) -> Tuple[Optional[Dict], str, Dict[str, Any]]:
        """
        Generate a candidate solution for the given constraints.
        
        This method constructs a detailed prompt with constraints, type hierarchy,
        and heap state information, then invokes the LLM to generate a solution.
        The LLM is encouraged to show reasoning before providing the final JSON.
        
        Args:
            constraints: List of constraint strings to satisfy
            type_hierarchy: Optional dict mapping variable names to type information
            heap_state: Optional dict with "aliases" and "objects" keys describing heap
            context: Optional reference information string
        
        Returns:
            Tuple of (parsed_json_dict, raw_llm_output_string, conversation_log)
            - parsed_json_dict is None if extraction fails
            - raw_llm_output_string contains the complete LLM response
            - conversation_log captures prompts/responses for logging
        
        Example:
            >>> solver = SolverAgent(llm)
            >>> result, raw = solver.solve(
            ...     constraints=["head(ref) != null", "head(ref).next(ref) == null"],
            ...     type_hierarchy={"head": "Type: LNode;\\nFields: next (LNode;)"}
            ... )
            >>> result["result"]
            'SAT'
            >>> result["valuation"][0]["variable"]
            'head(ref)'
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
            "CRITICAL VARIABLE NAMING RULES:\n"
            "1. ONLY use variable names that appear in the constraints (e.g., 'head(ref)', 'head(ref).next(ref)')\n"
            "2. NEVER invent new variable names like 'obj#1', 'obj1', 'node1', 'temp', etc.\n"
            "3. For field access chains, use the exact dot notation from constraints (e.g., 'head(ref).next(ref).next(ref)')\n"
            "4. If you need to represent an object's fields, describe them as part of the constraint-based variable\n"
            "5. Each unique variable from constraints gets ONE entry in the valuation\n\n"
            "EXAMPLES:\n"
            "✓ CORRECT: {\"variable\": \"head(ref)\", ...}\n"
            "✓ CORRECT: {\"variable\": \"head(ref).next(ref)\", ...}\n"
            "✗ WRONG: {\"variable\": \"obj#1\", ...}\n"
            "✗ WRONG: {\"variable\": \"node1\", ...}\n"
            "✗ WRONG: {\"variable\": \"temp\", ...}\n\n"
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
            
            parsed, _ = extract_first_json(raw_output)
            log_entry = {
                "agent": "solver",
                "stage": "solve",
                "system": system_prompt,
                "human": human_prompt,
                "response": raw_output,
            }
            return parsed, raw_output, log_entry
        except Exception as e:
            log_entry = {
                "agent": "solver",
                "stage": "solve",
                "system": system_prompt,
                "human": human_prompt,
                "response": "",
                "error": str(e),
            }
            return None, f"Error during Solver invocation: {str(e)}", log_entry
