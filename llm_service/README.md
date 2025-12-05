# LLM Constraint Solver Service

FastAPI service that uses LangChain + LLM (OpenAI/DeepSeek) to reason about high-level Java constraints.

## Features

- **Constraint Solving**: Determines satisfiability (SAT/UNSAT/UNKNOWN) of Java constraints
- **Valuation Generation**: Produces candidate valuations that satisfy constraints
- **Context Support**: Reads `ctx.md` file for reference information
- **Logging**: Automatically logs all requests/responses to markdown files in `log/` directory
- **Flexible LLM Support**: Supports OpenAI and DeepSeek (or any compatible API) via configuration

## Quick Start

1. Create a Python virtualenv and install dependencies:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

2. Configure the service by editing `config.py`:

```python
OPENAI_API_KEY = "sk-..."  # Your API key
LLM_MODEL = "deepseek-chat"  # or "gpt-4", "gpt-3.5-turbo", etc.
BASE_URL = "https://api.deepseek.com/v1"  # Optional: API endpoint URL
```

3. (Optional) Add context information by creating or editing `ctx.md`:

The service will automatically read `ctx.md` and include it as reference information in prompts.

4. Run the service (make sure you're in the `llm_service` directory):

```bash
cd llm_service
python -m uvicorn app:app --host 0.0.0.0 --port 8000
```

Alternatively, if you're already in the `llm_service` directory:
```bash
uvicorn app:app --host 0.0.0.0 --port 8000
```

## API Usage

### Endpoint: `POST /solve`

Accepts JSON request with constraints and optional base valuation.

**Request Format:**
```json
{
  "constraints": ["constraint1", "constraint2", ...],
  "valuation": {"key": "value", ...},  // Optional: base valuation
  "type_hierarchy": {"var": "type_info", ...},  // Optional: type hierarchy information
  "heap_state": {  // Optional: heap state information (NEW)
    "aliases": {"var": ref, ...},  // Variable to object reference mapping
    "objects": {  // Object reference to object description mapping
      "ref": {
        "class": "ClassName",
        "fields": {"field": value, ...}
      }
    }
  },
  "max_tokens": 512,  // Optional: max tokens for LLM response (default: 512)
  "temperature": 0.0  // Optional: temperature for LLM (default: 0.0)
}
```

**Response Format:**
```json
{
  "result": "SAT|UNSAT|UNKNOWN",
  "valuation": [{"key": "value", ...}],  // Array of valuation objects (only if SAT)
  "raw": "original LLM text"  // Optional: raw response if parsing failed
}
```

**Example Request:**
```bash
curl -X POST http://localhost:8000/solve \
  -H "Content-Type: application/json" \
  -d '{
    "constraints": [
      "obj(ref) instanceof LCar;",
      "obj.wheels_num == 4",
      "obj.highest_speed > 200"
    ],
    "valuation": {},
    "max_tokens": 512,
    "temperature": 0.0
  }'
```

**Example Response:**
```json
{
  "result": "SAT",
  "valuation": [
    {
      "obj(ref)": "LCar;",
      "obj.wheels_num": 4,
      "obj.highest_speed": 250
    }
  ]
}
```

## Important Notes

### Valuation Format

- Valuations are returned as an **array of objects**, where each object represents a symbolic object's properties
- Keys use **flat dot notation** (e.g., `"obj(ref)"`, `"x.field"`) - nested objects are automatically flattened
- Type constraints use the format: `"obj(ref)": "LCar;"` where the value is the Java type descriptor

### Constraint Types

The service handles various Java constraint types:
- **Type constraints**: `"x instanceof Ljava/util/List;"`
- **Null checks**: `"x == null"` or `"x != null"`
- **Field access**: `"x.field"` (requires x to be non-null)
- **Numeric constraints**: comparisons, arithmetic operations
- **Reference comparisons**: `"x == y"` or `"x != y"` (with heap state support)

### Heap State (NEW)

The service now supports **heap state information** to help the LLM understand object relationships and structure:

**What is heap state?**
- A snapshot of reachable objects from the current execution path
- Shows object references, fields, and relationships between objects
- Helps LLM reason about aliasing, cycles, and complex data structures

**Heap State Format:**
```json
{
  "aliases": {
    "head": 466,      // Variable 'head' points to object 466
    "slow": 469,      // Variable 'slow' points to object 469
    "fast": 469       // Variable 'fast' points to object 469 (aliasing!)
  },
  "objects": {
    "466": {
      "class": "ListNode",
      "fields": {
        "next": 469,  // Reference to object 469
        "val": 1      // Primitive value
      }
    },
    "469": {
      "class": "ListNode",
      "fields": {
        "next": 486,
        "val": 2
      }
    },
    "486": {
      "class": "ListNode",
      "fields": {
        "next": 469,  // Cycle! Points back to 469
        "val": 3
      }
    }
  }
}
```

**Use Cases for Heap State:**
- **Cycle detection**: Identify circular references in linked lists, graphs
- **Aliasing analysis**: Understand when multiple variables point to the same object
- **Structural reasoning**: Analyze tree, graph, or list structures
- **Field constraints**: Reason about field values in the context of object relationships

**Example with Heap State:**
```bash
curl -X POST http://localhost:8000/solve \
  -H "Content-Type: application/json" \
  -d '{
    "constraints": ["slow == fast", "slow != null"],
    "valuation": {"slow": 469, "fast": 469},
    "heap_state": {
      "aliases": {"slow": 469, "fast": 469},
      "objects": {
        "469": {"class": "ListNode", "fields": {"next": 486, "val": 2}},
        "486": {"class": "ListNode", "fields": {"next": 469, "val": 3}}
      }
    }
  }'
```

The LLM can now see that both `slow` and `fast` point to the same object (469), and that there's a cycle in the linked list structure.

### Logging

All requests and responses are automatically logged to `log/` directory:
- Logs are organized by date and session (e.g., `log/2025-01-15-1/`)
- Each log file includes:
  - Request/response JSON
  - Human message sent to LLM
  - LLM response message
  - Timestamps and duration

### Configuration

Configuration is managed via `config.py`:
- `OPENAI_API_KEY`: Your API key (required)
- `LLM_MODEL`: Model name (e.g., "deepseek-chat", "gpt-4", "gpt-3.5-turbo")
- `BASE_URL`: Optional API endpoint URL (for custom providers or proxies)

### Production Considerations

This is a template service. For production use, consider adding:
- Robust parsing and validation
- Verification steps (re-check candidate valuations with a symbolic solver)
- Retry logic and error handling
- Caching for repeated queries
- Rate limiting
- Authentication/authorization
