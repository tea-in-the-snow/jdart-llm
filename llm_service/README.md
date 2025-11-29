# LLM Constraint Solver Service

Minimal FastAPI service that uses LangChain + OpenAI to reason about high-level constraints.

Quick start:

1. Create a Python virtualenv and install dependencies:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

2. Set your OpenAI API key:

```bash
export OPENAI_API_KEY="sk-..."
```

3. Run the service:

```bash
uvicorn app:app --host 0.0.0.0 --port 8000
```

4. Example request (curl):

```bash
curl -X POST http://localhost:8000/solve -H "Content-Type: application/json" -d '{"constraints":["x > 0","x < 10"], "valuation": {"x": 5}}'
```

Notes:
- This is a template. You should add robust parsing, verification (re-check candidate valuations with a symbolic solver), retries, caching, and rate limiting for production use.
- The service requires `OPENAI_API_KEY`. Optionally set `LLM_MODEL` to choose another model supported by LangChain/OpenAI.
