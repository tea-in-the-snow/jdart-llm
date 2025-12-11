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
        parameter_type_constraints: Optional[Dict[str, str]] = None,
        source_context: Optional[Dict[str, Any]] = None,
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
            parameter_type_constraints: Optional dict mapping parameter names to their static types
            source_context: Optional dict with source code context (method_source, class_source, line_numbers, etc.)
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
            "TYPE DETERMINATION RULES:\n"
            "When determining the type for a reference variable (e.g., \"x(ref) instance A\"):\n"
            "1. If type A has subclasses (e.g., A1, A2), prefer using the PARENT type A unless constraints explicitly require a subclass\n"
            "2. Only use a subclass type if the constraints specifically require features unique to that subclass\n"
            "3. The general principle is: use the most general (parent) type that satisfies all constraints\n"
            "4. Example: If \"x(ref) instance A\" and A has subclass A1, use type A in the valuation, not A1\n\n"
            "EXAMPLES:\n"
            "✓ CORRECT: {\"variable\": \"head(ref)\", ...}\n"
            "✓ CORRECT: {\"variable\": \"head(ref).next(ref)\", ...}\n"
            "✗ WRONG: {\"variable\": \"obj#1\", ...}\n"
            "✗ WRONG: {\"variable\": \"node1\", ...}\n"
            "✗ WRONG: {\"variable\": \"temp\", ...}\n\n"
            "Reasoning is encouraged; you may show your work before the final JSON."
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
            param_type_block += "IMPORTANT: When generating the valuation, ensure that:\n"
            param_type_block += "1. Each parameter's actual type is compatible with (subtype of) its declared type\n"
            param_type_block += "2. For reference parameters like 'node(ref)', use the JVM type format (e.g., 'LListNode;')\n"
            param_type_block += "3. The type in the valuation entry must match or be a subtype of the declared type\n\n"
        
        type_hierarchy_block = ""
        if type_hierarchy:
            type_hierarchy_block = "Type Hierarchy Information:\n"
            for var_name, type_info in type_hierarchy.items():
                type_hierarchy_block += f"\nVariable: {var_name}\n{type_info}\n"
            type_hierarchy_block += "\n"
        
        source_context_block = ""
        if source_context:
            source_context_block = "Source Code Context:\n"
            source_context_block += "This is the actual source code of the method and class being analyzed.\n"
            source_context_block += "Use this to understand the code structure, logic, and relationships.\n\n"
            
            if "method_name" in source_context:
                source_context_block += f"Method: {source_context['method_name']}\n"
            if "class_name" in source_context:
                source_context_block += f"Class: {source_context['class_name']}\n"
            if "source_file" in source_context:
                source_context_block += f"File: {source_context['source_file']}\n"
            
            if "line_numbers" in source_context:
                line_info = source_context["line_numbers"]
                if isinstance(line_info, dict):
                    source_context_block += f"Lines: {line_info.get('method_start', '?')}-{line_info.get('method_end', '?')}\n"
            
            source_context_block += "\n"
            
            if "method_source" in source_context and source_context["method_source"]:
                source_context_block += "Method Source Code:\n"
                source_context_block += "```java\n"
                source_context_block += source_context["method_source"]
                source_context_block += "```\n\n"
            
            if "class_source" in source_context and source_context["class_source"]:
                source_context_block += "Complete Class Source Code:\n"
                source_context_block += "```java\n"
                source_context_block += source_context["class_source"]
                source_context_block += "```\n\n"
            
            if "related_classes" in source_context and source_context["related_classes"]:
                source_context_block += "Related Classes (referenced in constraints):\n"
                related = source_context["related_classes"]
                if isinstance(related, dict):
                    for class_name, class_source in related.items():
                        if class_source:
                            source_context_block += f"\nClass: {class_name}\n"
                            source_context_block += "```java\n"
                            source_context_block += class_source
                            source_context_block += "```\n"
                source_context_block += "\n"
        
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
            f"{source_context_block}"
            f"{param_type_block}"
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
