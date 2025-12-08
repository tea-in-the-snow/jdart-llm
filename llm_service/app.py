"""
Simple FastAPI service that wraps an LLM (via LangChain) to reason about high-level constraints.

Endpoints:
- POST /solve : Accepts JSON {"constraints": ["..."], "valuation": {...}} and returns JSON {"result":"SAT|UNSAT|UNKNOWN", "valuation": {...}, "raw": "original LLM text"}

Configuration:
- Configure `OPENAI_API_KEY` and `LLM_MODEL` in `config.py`.

This is a minimal template to get started and should be extended with robust parsing, validation,
and optional verification steps (e.g., re-check candidate valuations with a symbolic solver).
"""

from fastapi import FastAPI
from pydantic import BaseModel
from typing import List, Dict, Any, Optional
import re
import json
from datetime import datetime
import os

from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage

from config import OPENAI_API_KEY, LLM_MODEL, BASE_URL
from logger import write_log

app = FastAPI()

def flatten_valuation(valuation: Dict[str, Any], prefix: str = "") -> Dict[str, Any]:
    """
    Flatten a nested valuation dictionary into flat keys with dot notation.
    Example: {"obj": {"<ref>": "LCar;"}} -> {"obj(ref)": "LCar;"}
    """
    if not isinstance(valuation, dict):
        return valuation
    
    flattened = {}
    for key, value in valuation.items():
        new_key = f"{prefix}.{key}" if prefix else key
        
        if isinstance(value, dict):
            # Recursively flatten nested dictionaries
            flattened.update(flatten_valuation(value, new_key))
        else:
            # Leaf value, add to flattened dict
            flattened[new_key] = value
    
    return flattened

class SolveRequest(BaseModel):
    constraints: List[str]
    valuation: Optional[Dict[str, Any]] = None
    type_hierarchy: Optional[Dict[str, str]] = None
    heap_state: Optional[Dict[str, Any]] = None
    max_tokens: Optional[int] = 512
    temperature: Optional[float] = 0.0

@app.post("/solve")
async def solve(req: SolveRequest):
    started_time = datetime.now()
    
    # Prepare request data for logging
    request_data = {
        "constraints": req.constraints,
        "valuation": req.valuation,
        "type_hierarchy": req.type_hierarchy,
        "heap_state": req.heap_state
    }
    
    # Read the content of ctx.md file as reference information
    ctx_content = ""
    ctx_file_path = os.path.join(os.path.dirname(__file__), 'ctx.md')
    try:
        if os.path.exists(ctx_file_path):
            with open(ctx_file_path, 'r', encoding='utf-8') as f:
                ctx_content = f.read()
    except Exception as e:
        print(f"Error reading ctx.md file: {e}")
    
    # Build a concise prompt that asks the LLM to respond with JSON.
    system_instructions = (
        "You are a constraint-solving assistant specialized in reasoning about high-level Java constraints. "
        "Your task is to determine satisfiability of the given constraints and, if SAT, to propose a candidate "
        "valuation that satisfies all constraints.\n\n"
        "Instructions:\n"
        "1) Constraint categories (analyze all carefully):\n"
        "   - Type: \"x instanceof Ljava/util/List;\" → x must have type Ljava/util/List;\n"
        "   - Null: \"x == null\" or \"x != null\"; field access implies receiver non-null.\n"
        "   - Field access: \"x.field\" uses dot-notation; if referenced, x != null.\n"
        "   - Numeric: comparisons, arithmetic; keep numbers as JSON numbers.\n"
        "   - Type hierarchy: when provided, use it to ensure assigned reference types are compatible with inheritance.\n"
        "   - Heap state: when provided, use it to understand object relationships, aliasing, and heap structure.\n\n"
        "2) Assigning reference types (instanceof):\n"
        "   - Use FLAT keys with dot notation only, never nested.\n"
        "   - If \"obj(ref) instanceof LCar;\", set {\"obj(ref)\": \"LCar;\"}.\n"
        "   - Ensure the assigned type is allowed by the provided type_hierarchy (if any).\n"
        "   - When the constraint targets an INTERFACE or ABSTRACT type, prefer assigning a CONCRETE class that implements/extends it (i.e., return an instantiable class, not an interface/abstract).\n\n"
        "3) Null handling for references:\n"
        "   - For any \"<name>(ref) == null\", set \"<name>(ref)\": \"null\" (string).\n"
        "   - Non-null constraints imply the key exists and is not \"null\".\n\n"
        "4) Valuation construction:\n"
        "   - Provide any assignments needed to satisfy the constraints.\n"
        "   - Use dot-notation for fields: e.g., \"x.field\": 3, \"y.next(ref)\": \"LNode;\".\n"
        "   - Strings for types and \"null\"; keep numbers/booleans as native JSON types.\n\n"
        "5) Output: return ONE top-level JSON object ONLY (no extra text/markdown). Allowed formats:\n"
        "   - SAT: {\"result\": \"SAT\", \"valuation\": [{...}, {...}]}\n"
        "     • \"valuation\" is an ARRAY; each element is a flat object for one symbolic entity.\n"
        "   - UNSAT: {\"result\": \"UNSAT\"}\n"
        "   - UNKNOWN: {\"result\": \"UNKNOWN\", \"raw\": \"short explanation\"}\n\n"
        "6) Strict format requirements (guardrails):\n"
        "   - No nested objects inside valuation; flatten all properties into dot-notation keys.\n"
        "   - No keys other than \"result\", \"valuation\" (array), and optionally \"raw\" (string).\n"
        "   - Keep values JSON-safe; avoid quotes around numbers/booleans.\n"
        "   - If unsure, use UNKNOWN and place brief reasoning in \"raw\".\n\n"
        "Examples:\n"
        "SAT example:\n"
        "{\"result\":\"SAT\", \"valuation\": [\n"
        "  {\"obj(ref)\": \"Ljava/lang/Object;\"},\n"
        "  {\"cell(ref)\": \"null\", \"x.field\": 5}\n"
        "]}\n"
        "Interface instanceof example (prefer concrete):\n"
        "{\"result\":\"SAT\", \"valuation\": [\n"
        "  {\"list(ref)\": \"Ljava/util/ArrayList;\"}\n"
        "]}\n"
        "UNSAT example:\n"
        "{\"result\":\"UNSAT\"}\n"
        "UNKNOWN example:\n"
        "{\"result\":\"UNKNOWN\", \"raw\": \"Ambiguous type constraints between LList; and LArrayList;\"}"
    )

    constraints_block = "\n".join(f"- {c}" for c in req.constraints)
    # Build type hierarchy block if provided
    type_hierarchy_block = ""
    if req.type_hierarchy:
        type_hierarchy_block = "Type Hierarchy Information:\n"
        for var_name, type_info in req.type_hierarchy.items():
            type_hierarchy_block += f"\nVariable: {var_name}\n{type_info}\n"
        type_hierarchy_block += "\n"
    
    # Build heap state block if provided
    heap_state_block = ""
    if req.heap_state:
        heap_state_block = "Heap State Information:\n"
        heap_state_block += "This shows the current state of reachable objects in the heap.\n\n"
        
        # Add aliases (variable -> object reference mapping)
        if "aliases" in req.heap_state:
            aliases = req.heap_state["aliases"]
            if aliases:
                heap_state_block += "Aliases (variable → object reference):\n"
                for var_name, obj_ref in aliases.items():
                    heap_state_block += f"  {var_name} → {obj_ref}\n"
                heap_state_block += "\n"
        
        # Add objects (object reference -> object description mapping)
        if "objects" in req.heap_state:
            objects = req.heap_state["objects"]
            if objects:
                heap_state_block += "Objects (reference → structure):\n"
                for obj_ref, obj_desc in objects.items():
                    class_name = obj_desc.get("class", "Unknown")
                    heap_state_block += f"  {obj_ref}: {class_name}\n"
                    
                    # Add fields
                    fields = obj_desc.get("fields", {})
                    if fields:
                        for field_name, field_value in fields.items():
                            heap_state_block += f"    {field_name}: {field_value}\n"
                    
                    # Add array elements if present
                    if "elements" in obj_desc:
                        elements = obj_desc["elements"]
                        heap_state_block += f"    elements: {elements}\n"
                    
                    # Add array length if present
                    if "length" in obj_desc:
                        length = obj_desc["length"]
                        heap_state_block += f"    length: {length}\n"
                    
                    heap_state_block += "\n"
        
        heap_state_block += "Use this heap information to:\n"
        heap_state_block += "- Understand reference relationships between objects\n"
        heap_state_block += "- Identify aliasing (multiple variables pointing to the same object)\n"
        heap_state_block += "- Detect cycles or structural patterns (e.g., linked list loops)\n"
        heap_state_block += "- Reason about field values and object states\n\n"

    # If there is ctx.md content, add it to the prompt
    context_block = ""
    if ctx_content:
        context_block = f"Reference information:\n{ctx_content}\n\n"
    
    human = (
        f"{context_block}"
        f"{type_hierarchy_block}"
        f"{heap_state_block}"
        f"Constraints:\n{constraints_block}\n\n"
        "Please produce JSON only. If uncertain, return {\"result\":\"UNKNOWN\", \"raw\": \"explain...\"}."
    )
    
    # Full human message including system instructions (for LLM)
    full_human_message = system_instructions + "\n\n" + human
    # Human message for logging (without system instructions)
    human_message_for_log = human

    if not OPENAI_API_KEY:
        response_data = {"result": "UNKNOWN", "error": "OPENAI_API_KEY not configured in config.py"}
        ended_time = datetime.now()
        write_log(
            request=request_data,
            response=response_data,
            started_time=started_time,
            ended_time=ended_time,
            human_message=human_message_for_log,
            llm_message=None
        )
        return response_data

    model = LLM_MODEL

    llm_kwargs = {
        "temperature": req.temperature or 0.0,
        "max_tokens": req.max_tokens or 512,
        "model": model,
        "api_key": OPENAI_API_KEY
    }
    if BASE_URL:
        llm_kwargs["base_url"] = BASE_URL
    
    llm = ChatOpenAI(**llm_kwargs)

    try:
        response = llm.invoke([HumanMessage(content=full_human_message)])
        text = response.content if hasattr(response, 'content') else str(response)
        llm_message = text
    except Exception as e:
        response_data = {"result": "UNKNOWN", "error": str(e)}
        ended_time = datetime.now()
        write_log(
            request=request_data,
            response=response_data,
            started_time=started_time,
            ended_time=ended_time,
            human_message=human_message_for_log,
            llm_message=None
        )
        return response_data

    # Extract JSON object from the LLM output
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if m:
        json_str = m.group(0)
        try:
            parsed = json.loads(json_str)
            # Process valuation: convert to array format if needed
            if "valuation" in parsed:
                valuation = parsed["valuation"]
                if isinstance(valuation, dict):
                    # If LLM returned a single object, convert to array with one element
                    # First flatten it, then wrap in array
                    flattened = flatten_valuation(valuation)
                    parsed["valuation"] = [flattened]
                elif isinstance(valuation, list):
                    # If already an array, flatten each object in the array
                    parsed["valuation"] = [
                        flatten_valuation(item) if isinstance(item, dict) else item
                        for item in valuation
                    ]
                # If it's neither dict nor list, leave it as is (might be null or other type)
            response_data = parsed
        except Exception as e:
            response_data = {"result": "UNKNOWN", "raw": text, "parse_error": str(e)}
    else:
        response_data = {"result": "UNKNOWN", "raw": text}
    
    ended_time = datetime.now()
    
    # Write log
    write_log(
        request=request_data,
        response=response_data,
        started_time=started_time,
        ended_time=ended_time,
        human_message=human_message_for_log,
        llm_message=llm_message
    )
    
    return response_data
