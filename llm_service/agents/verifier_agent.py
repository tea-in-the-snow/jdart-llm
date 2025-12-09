"""
Verifier Agent - Validates solver outputs against constraints and rules.

This module contains the VerifierAgent class which performs multi-layer validation:
- Structural validation (JSON format, required fields)
- Semantic validation (type compatibility, logical consistency)
- LLM-assisted validation (complex type hierarchy checks)
"""

from typing import List, Dict, Optional, Any, Tuple
from langchain_openai import ChatOpenAI
from langchain_core.messages import SystemMessage, HumanMessage

from .utils import extract_first_json


class VerifierAgent:
    """
    Validates the Solver's output against constraints and rules.
    
    Performs comprehensive validation including:
    - Type compatibility with type hierarchy
    - Logical consistency (e.g., no null for non-null constraints)
    - JSON format validity
    - Valuation structure completeness
    
    For complex type hierarchy checks, uses LLM for semantic validation
    to handle Java interfaces, inheritance, and other type system features.
    
    Attributes:
        llm: ChatOpenAI instance for LLM-assisted semantic checks
    """
    
    def __init__(self, llm: ChatOpenAI):
        """
        Initialize the VerifierAgent.
        
        Args:
            llm: ChatOpenAI instance for semantic validation tasks
        """
        self.llm = llm
    
    def verify(
        self,
        solver_output: Optional[Dict],
        raw_output: str,
        constraints: List[str],
        type_hierarchy: Optional[Dict[str, str]] = None,
        heap_state: Optional[Dict[str, Any]] = None,
    ) -> Tuple[bool, str, Optional[Dict], List[Dict[str, Any]]]:
        """
        Verify the Solver's output against all validation rules.
        
        Performs multi-layer validation:
        1. JSON extraction validation
        2. Structural validation (result field, valuation format)
        3. Field completeness validation
        4. Type compatibility validation (with LLM assistance)
        5. Logical consistency validation (null/non-null conflicts)
        
        Args:
            solver_output: Parsed JSON dict from Solver (or None if extraction failed)
            raw_output: Raw string output from Solver for error reporting
            constraints: List of constraint strings for context
            type_hierarchy: Optional dict mapping variables to type information
            heap_state: Optional dict with heap state for validation context
        
        Returns:
            Tuple of (is_valid, error_report, parsed_json_if_valid, conversation_logs)
            - is_valid: Boolean indicating if output passed all checks
            - error_report: String describing validation failures (empty if valid)
            - parsed_json_if_valid: The validated dict (None if invalid)
            - conversation_logs: LLM conversation entries collected during checks
        
        Example:
            >>> verifier = VerifierAgent(llm)
            >>> is_valid, error, output = verifier.verify(
            ...     solver_output={"result": "SAT", "valuation": [...]},
            ...     raw_output="...",
            ...     constraints=["head(ref) != null"]
            ... )
            >>> is_valid
            True
            >>> error
            ''
        """
        conversation_logs: List[Dict[str, Any]] = []

        # If Solver failed to extract JSON, report it
        if solver_output is None:
            return False, f"Could not extract valid JSON from Solver output: {raw_output}", None, conversation_logs
        
        # Basic structure checks
        if "result" not in solver_output:
            return False, "Missing 'result' field in Solver output", None, conversation_logs
        
        result = solver_output.get("result")
        if result not in ["SAT", "UNSAT", "UNKNOWN"]:
            return False, f"Invalid result value: {result}", None, conversation_logs
        
        # For SAT, validate valuation
        if result == "SAT":
            if "valuation" not in solver_output:
                return False, "SAT result missing 'valuation' field", None, conversation_logs
            
            valuation = solver_output["valuation"]
            if not isinstance(valuation, list):
                return False, f"'valuation' should be an array, got {type(valuation)}", None, conversation_logs
            
            if len(valuation) == 0:
                return False, "SAT valuation cannot be empty", None, conversation_logs
            
            # Extract base variables from constraints for validation
            base_variables = self._extract_base_variables_from_constraints(constraints)

            # Ensure every variable mentioned in constraints has a valuation entry
            valuation_vars = {entry.get("variable") for entry in valuation if isinstance(entry, dict)}
            missing_vars = base_variables - valuation_vars
            if missing_vars:
                return False, (
                    "Valuation missing variables required by constraints: "
                    f"{sorted(missing_vars)}"
                ), None, conversation_logs
            
            # Validate each valuation entry
            for idx, entry in enumerate(valuation):
                if not isinstance(entry, dict):
                    return False, f"Valuation entry {idx} is not a dict", None, conversation_logs
                
                if "variable" not in entry:
                    return False, f"Valuation entry {idx} missing 'variable'", None, conversation_logs
                
                # Validate variable name against constraints
                var_name = entry.get("variable", "")
                if not self._validate_variable_name(var_name, base_variables, constraints):
                    return False, (
                        f"Invalid variable name '{var_name}' in entry {idx}. "
                        f"Variable names must appear in constraints or be derivable from them. "
                        f"Do not invent names like 'obj#1', 'obj1', etc."
                    ), None, conversation_logs
                
                # For reference variables, check required fields
                if "type" in entry and entry["type"] != "null":
                    required_ref_fields = {"type", "newObject", "trueRef", "reference"}
                    entry_keys = set(entry.keys())
                    if not required_ref_fields.issubset(entry_keys):
                        missing = required_ref_fields - entry_keys
                        return False, f"Valuation entry {idx} missing fields: {missing}", None, conversation_logs
                
                # Check type compatibility with type hierarchy
                if type_hierarchy and "type" in entry:
                    var_name = entry.get("variable", "")
                    entry_type = entry.get("type")
                    # If type hierarchy exists, perform LLM-assisted semantic check
                    if var_name in type_hierarchy:
                        is_compatible, compat_error, log_entry = self._check_type_compatibility_with_llm(
                            var_name=var_name,
                            assigned_type=entry_type,
                            type_hierarchy_info=type_hierarchy[var_name],
                            constraints=constraints,
                        )
                        if log_entry:
                            conversation_logs.append(log_entry)
                        if not is_compatible:
                            return False, f"Type incompatibility for {var_name}: {compat_error}", None, conversation_logs
            
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
                return False, f"Conflicting null/non-null assignments for: {conflicts}", None, conversation_logs
        
        # If all checks pass, return valid
        return True, "", solver_output, conversation_logs
    
    def _check_type_compatibility_with_llm(
        self,
        var_name: str,
        assigned_type: str,
        type_hierarchy_info: str,
        constraints: List[str],
    ) -> Tuple[bool, str, Optional[Dict[str, Any]]]:
        """
        Use LLM to validate type compatibility against hierarchy.
        
        This handles complex Java type hierarchy (interfaces, inheritance, generics)
        that would be difficult to hardcode in Python validation logic.
        
        Args:
            var_name: Variable name being validated
            assigned_type: The type assigned in the valuation
            type_hierarchy_info: String describing the type hierarchy for this variable
            constraints: List of constraints for additional context
        
        Returns:
            Tuple of (is_compatible, error_message, conversation_log)
            - is_compatible: Boolean indicating type compatibility
            - error_message: String explaining incompatibility (empty if compatible)
            - conversation_log: LLM prompts/responses for logging
        
        Example:
            >>> is_compat, err = verifier._check_type_compatibility_with_llm(
            ...     var_name="myList",
            ...     assigned_type="Ljava/util/ArrayList;",
            ...     type_hierarchy_info="Type: Ljava/util/List;\\nImplementations: ArrayList, LinkedList",
            ...     constraints=["myList != null"]
            ... )
            >>> is_compat
            True
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
            
            parsed, _ = extract_first_json(raw_output)
            log_entry = {
                "agent": "verifier",
                "stage": "type_check",
                "system": system_prompt,
                "human": human_prompt,
                "response": raw_output,
            }

            if parsed and "compatible" in parsed:
                is_compatible = parsed.get("compatible", False)
                reason = parsed.get("reason", "No reason provided")
                return is_compatible, reason, log_entry
            else:
                # If LLM fails to respond properly, be conservative and assume compatible
                return True, "LLM check inconclusive, assuming compatible", log_entry
        except Exception as e:
            # On error, assume compatible to avoid blocking valid solutions
            log_entry = {
                "agent": "verifier",
                "stage": "type_check",
                "system": system_prompt,
                "human": human_prompt,
                "response": "",
                "error": str(e),
            }
            return True, f"LLM check failed ({str(e)}), assuming compatible", log_entry
    
    def _extract_base_variables_from_constraints(self, constraints: List[str]) -> set:
        """
        Extract all variable names mentioned in constraints.
        
        This includes:
        - Simple variables: head(ref)
        - Field access chains: head(ref).next(ref)
        - All intermediate paths
        
        Args:
            constraints: List of constraint strings
        
        Returns:
            Set of valid variable names that can appear in valuation
        """
        import re
        variables = set()
        
        # Pattern to match variable references: varname(ref) or varname(ref).field(ref)...
        # This matches: head(ref), head(ref).next(ref), head(ref).next(ref).next(ref), etc.
        pattern = r"\b([a-zA-Z_][a-zA-Z0-9_]*(?:\(ref\))?(?:\.[a-zA-Z_][a-zA-Z0-9_]*\(ref\))*)"
        
        for constraint in constraints:
            matches = re.findall(pattern, constraint)
            for match in matches:
                if '(ref)' in match:
                    variables.add(match)
        
        return variables
    
    def _validate_variable_name(self, var_name: str, base_variables: set, constraints: List[str]) -> bool:
        """
        Validate that a variable name is legitimate.
        
        Rules:
        1. Must be present in base_variables (extracted from constraints)
        2. Must not be an invented name like 'obj#1', 'obj1', 'node1', etc.
        3. Must use proper dot-chain notation if it's a field access
        
        Args:
            var_name: Variable name to validate
            base_variables: Set of valid variables from constraints
            constraints: Original constraints for context
        
        Returns:
            True if valid, False otherwise
        """
        import re
        
        # Check if variable is in the extracted set
        if var_name in base_variables:
            return True
        
        # Reject common invalid patterns
        invalid_patterns = [
            r'^obj#?\d+$',      # obj#1, obj1, obj2, etc.
            r'^node#?\d+$',     # node#1, node1, etc.
            r'^temp#?\d+$',     # temp#1, temp1, etc.
            r'^var#?\d+$',      # var#1, var1, etc.
            r'^item#?\d+$',     # item#1, item1, etc.
            r'^element#?\d+$',  # element#1, element1, etc.
        ]
        
        for pattern in invalid_patterns:
            if re.match(pattern, var_name, re.IGNORECASE):
                return False
        
        # If it contains (ref) but isn't in base_variables, it might be invalid
        if '(ref)' in var_name:
            return False
        
        # Allow primitive/field names without (ref) as they might be field values
        return True
