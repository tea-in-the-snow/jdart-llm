"""
Multi-Agent Constraint Solver using LLM (FastAPI + LangChain).

Architecture:
- Solver Agent: Generates candidate valuations for constraints (allows Chain of Thought).
- Verifier Agent: Validates the Solver's output against type hierarchy, heap state, and format rules.
- Refiner Agent: Corrects Solver's output based on Verifier feedback (up to N retries).

Workflow:
  Solver -> Verifier -> (Pass) -> Return
  Solver -> Verifier (Fail) -> Refiner -> Solver -> ... (max 2 retries by default)

Endpoints:
- POST /solve : Constraint solving with multi-agent workflow
              Input: {"constraints": [...], "type_hierarchy": {...}, "heap_state": {...}, "max_tokens": 512, "temperature": 0.0}
              Output: {"result": "SAT|UNSAT|UNKNOWN", "valuation": [...], "raw": "...", "iterations": N, "verification_error": "..."}

Configuration:
- Configure `OPENAI_API_KEY` and `LLM_MODEL` in `config.py`.
"""

from fastapi import FastAPI
from pydantic import BaseModel
from typing import List, Dict, Any, Optional
import json
from datetime import datetime
import os

from langchain_openai import ChatOpenAI

from config import OPENAI_API_KEY, LLM_MODEL, BASE_URL
from logger import write_log
from agents import MultiAgentOrchestrator

app = FastAPI()


class SolveRequest(BaseModel):
    constraints: List[str]
    valuation: Optional[Dict[str, Any]] = None
    type_hierarchy: Optional[Dict[str, str]] = None
    heap_state: Optional[Dict[str, Any]] = None
    max_tokens: Optional[int] = 512
    temperature: Optional[float] = 0.0

@app.post("/solve")
async def solve(req: SolveRequest):
    """
    Multi-Agent Constraint Solver Endpoint.
    
    Workflow:
    1. Solver Agent generates candidate solution with Chain of Thought.
    2. Verifier Agent validates against constraints and format rules.
    3. If valid, return the solution.
    4. If invalid, Refiner Agent corrects it and loop (max 2 retries).
    """
    started_time = datetime.now()
    
    # Prepare request data for logging
    request_data = {
        "constraints": req.constraints,
        "type_hierarchy": req.type_hierarchy,
        "heap_state": req.heap_state,
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
    
    # Validate API key
    if not OPENAI_API_KEY:
        response_data = {"result": "UNKNOWN", "error": "OPENAI_API_KEY not configured in config.py"}
        ended_time = datetime.now()
        write_log(
            request=request_data,
            response=response_data,
            started_time=started_time,
            ended_time=ended_time,
            human_message="(API key missing)",
            llm_message=None
        )
        return response_data
    
    # Initialize LLM
    model = LLM_MODEL
    llm_kwargs = {
        "temperature": req.temperature or 0.0,
        "max_tokens": req.max_tokens or 512,
        "model": model,
        "api_key": OPENAI_API_KEY,
    }
    if BASE_URL:
        llm_kwargs["base_url"] = BASE_URL
    
    llm = ChatOpenAI(**llm_kwargs)
    
    # Run multi-agent orchestrator
    try:
        orchestrator = MultiAgentOrchestrator(llm=llm, max_retries=2)
        response_data = orchestrator.solve(
            constraints=req.constraints,
            type_hierarchy=req.type_hierarchy,
            heap_state=req.heap_state,
            context=ctx_content,
        )
        llm_message = response_data.get("raw", "")
    except Exception as e:
        response_data = {"result": "UNKNOWN", "error": str(e)}
        llm_message = None
    
    ended_time = datetime.now()
    
    # Write log
    write_log(
        request=request_data,
        response=response_data,
        started_time=started_time,
        ended_time=ended_time,
        human_message=f"Constraints: {req.constraints}",
        llm_message=llm_message,
    )
    
    return response_data
