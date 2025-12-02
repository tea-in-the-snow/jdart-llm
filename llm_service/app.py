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
    Example: {"obj": {"<ref>": "LCar;"}} -> {"obj.<ref>": "LCar;"}
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
    max_tokens: Optional[int] = 512
    temperature: Optional[float] = 0.0

@app.post("/solve")
async def solve(req: SolveRequest):
    started_time = datetime.now()
    
    # Prepare request data for logging
    request_data = {
        "constraints": req.constraints,
        "valuation": req.valuation
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
        "Your task is to determine whether the given constraints are satisfiable under the optional base valuation, "
        "and if satisfiable, provide a candidate valuation that satisfies all constraints.\n\n"
        "Instructions:\n"
        "1. Analyze all constraints carefully. Common constraint types include:\n"
        "   - Type constraints: \"x instanceof Ljava/util/List;\" means x must be an instance of List\n"
        "   - Null checks: \"x == null\" or \"x != null\"\n"
        "   - Field access: \"x.field\" where x must be non-null\n"
        "   - Numeric constraints: comparisons, arithmetic operations\n\n"
        "2. The base valuation is only a starting point, not fixed truth. You are allowed (and expected) to MODIFY it\n"
        "   as needed to satisfy the constraints, as long as you keep changes minimal and consistent.\n"
        "   Example: if the constraint is \"('cell.<ref>' == null)\" and the base valuation contains\n"
        "   \"cell.<ref>\": 458, you SHOULD treat this as SAT by changing it to \"cell.<ref>\": \"null\" in the result.\n\n"
        "3. For type constraints (instanceof), assign the reference variable appropriately:\n"
        "   - If the constraint is \"obj.<ref> instanceof LCar;\", assign \"obj.<ref>\" as a flat key (not nested) to the type string \"LCar;\" in the valuation\n"
        "   - Use flat keys with dot notation: {\"obj.<ref>\": \"LCar;\"} NOT nested objects: {\"obj\": {\"<ref>\": \"LCar;\"}}\n"
        "   - Ensure the assigned type is compatible with the constraint\n\n"
        "4. For null constraints on reference variables, assign the string \"null\" explicitly:\n"
        "   - If there is a constraint like \"cell.<ref> == null\", then in the valuation return \"cell.<ref>\": \"null\"\n"
        "   - More generally, for any \"<name>.<ref> == null\" constraint, set \"<name>.<ref>\" to the string \"null\" in JSON\n\n"
        "5. Build upon the base valuation if provided, extending or modifying it as needed to satisfy all constraints.\n\n"
        "6. Output format: Respond ONLY with valid JSON, no additional text. Use one of these formats:\n"
        "   - {\"result\":\"SAT\", \"valuation\": [{...}, {...}]} when constraints are satisfiable\n"
        "     The valuation should be an ARRAY of objects, where each object represents a symbolic object's valuation.\n"
        "     Example: \"valuation\": [{\"obj1.<ref>\": \"Ljava/lang/Object;\"}, {\"obj2.<ref>\": \"Ljava/lang/Object;\"}]\n"
        "   - {\"result\":\"UNSAT\"} when constraints are unsatisfiable even after adjusting the valuation\n"
        "   - {\"result\":\"UNKNOWN\", \"raw\": \"explanation\"} when you cannot determine satisfiability\n\n"
        "7. The valuation array should contain objects with FLAT keys using dot notation (e.g., \"obj.<ref>\", \"x.field\") mapping to their values.\n"
        "   IMPORTANT: Do NOT use nested objects in the valuation. Always flatten nested properties into dot-notation keys.\n"
        "   Each element in the valuation array should be a separate object representing one symbolic object's properties.")

    constraints_block = "\n".join(f"- {c}" for c in req.constraints)
    valuation_block = "" if not req.valuation else "\n".join(f"{k} = {v}" for k, v in req.valuation.items())

    # If there is ctx.md content, add it to the prompt
    context_block = ""
    if ctx_content:
        context_block = f"Reference information:\n{ctx_content}\n\n"
    
    human = (
        f"{context_block}"
        f"Constraints:\n{constraints_block}\n\n"
        f"Base valuation (may be empty):\n{valuation_block}\n\n"
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
