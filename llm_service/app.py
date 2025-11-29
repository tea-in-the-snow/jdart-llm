"""
Simple FastAPI service that wraps an LLM (via LangChain) to reason about high-level constraints.

Endpoints:
- POST /solve : Accepts JSON {"constraints": ["..."], "valuation": {...}} and returns JSON {"result":"SAT|UNSAT|UNKNOWN", "valuation": {...}, "raw": "original LLM text"}

Configuration:
- Set `OPENAI_API_KEY` environment variable for the OpenAI provider.
- Optionally set `LLM_MODEL` env var (default: "gpt-4o-mini" or other supported model by langchain/OpenAI).

This is a minimal template to get started and should be extended with robust parsing, validation,
and optional verification steps (e.g., re-check candidate valuations with a symbolic solver).
"""

from fastapi import FastAPI
from pydantic import BaseModel
from typing import List, Dict, Any, Optional
import os
import re
import json

from langchain.chat_models import ChatOpenAI
from langchain.schema import HumanMessage

app = FastAPI()

class SolveRequest(BaseModel):
    constraints: List[str]
    valuation: Optional[Dict[str, Any]] = None
    max_tokens: Optional[int] = 512
    temperature: Optional[float] = 0.0

@app.post("/solve")
async def solve(req: SolveRequest):
    # Build a concise prompt that asks the LLM to respond with JSON.
    system_instructions = (
        "You are a constraint-solving assistant. Given high-level constraints and an optional base valuation, "
        "determine whether the high-level constraints are satisfiable under the supplied valuation. "
        "If satisfiable, return a candidate valuation. Your final output must be valid JSON and nothing else. "
        "Respond only with a JSON object like {\"result\":\"SAT\", \"valuation\": { ... }} or {\"result\":\"UNSAT\"}.")

    constraints_block = "\n".join(f"- {c}" for c in req.constraints)
    valuation_block = "" if not req.valuation else "\n".join(f"{k} = {v}" for k, v in req.valuation.items())

    human = (
        f"Constraints:\n{constraints_block}\n\n"
        f"Base valuation (may be empty):\n{valuation_block}\n\n"
        "Please produce JSON only. If uncertain, return {\"result\":\"UNKNOWN\", \"raw\": \"explain...\"}."
    )

    openai_key = os.environ.get("OPENAI_API_KEY")
    if not openai_key:
        return {"result": "UNKNOWN", "error": "OPENAI_API_KEY not set"}

    model = os.environ.get("LLM_MODEL", "gpt-4o-mini")

    llm = ChatOpenAI(temperature=req.temperature or 0.0, max_tokens=req.max_tokens or 512, model_name=model, openai_api_key=openai_key)

    try:
        response = llm.predict_messages([HumanMessage(content=system_instructions + "\n\n" + human)])
        text = response.content if hasattr(response, 'content') else str(response)
    except Exception as e:
        return {"result": "UNKNOWN", "error": str(e)}

    # Extract JSON object from the LLM output
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if m:
        json_str = m.group(0)
        try:
            parsed = json.loads(json_str)
            return parsed
        except Exception as e:
            return {"result": "UNKNOWN", "raw": text, "parse_error": str(e)}

    return {"result": "UNKNOWN", "raw": text}
