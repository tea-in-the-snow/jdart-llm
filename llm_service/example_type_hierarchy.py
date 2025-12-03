#!/usr/bin/env python3
"""
Example: How to manually send type hierarchy information to the LLM solver.

This demonstrates what the Java side automatically does when collecting
type information and sending it to the solver.
"""

import requests
import json

def example_with_arraylist():
    """Example: ArrayList type hierarchy for instanceof constraint."""
    
    print("="*70)
    print("Example 1: ArrayList with instanceof constraint")
    print("="*70)
    
    payload = {
        "constraints": [
            "list.<ref> instanceof Ljava/util/ArrayList;",
            "list.<ref> != null"
        ],
        "valuation": {
            "list.<ref>": 456
        },
        "type_hierarchy": {
            "list": """Type: java.util.ArrayList (signature: Ljava/util/ArrayList;)
  Extends: java.util.AbstractList
  Implements: java.util.List, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable
  Class hierarchy: java.util.ArrayList -> java.util.AbstractList -> java.util.AbstractCollection -> java.lang.Object
  All interfaces: java.util.List, java.util.Collection, java.lang.Iterable, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable"""
        }
    }
    
    print("\nPayload:")
    print(json.dumps(payload, indent=2))
    
    send_request(payload)

def example_with_null_constraint():
    """Example: Null constraint with type hierarchy."""
    
    print("\n" + "="*70)
    print("Example 2: Null constraint with type hierarchy")
    print("="*70)
    
    payload = {
        "constraints": [
            "cell.<ref> == null"
        ],
        "valuation": {
            "cell.<ref>": 789
        },
        "type_hierarchy": {
            "cell": """Type: java.lang.Object (signature: Ljava/lang/Object;)
  Class hierarchy: java.lang.Object
  All interfaces: (none)"""
        }
    }
    
    print("\nPayload:")
    print(json.dumps(payload, indent=2))
    
    send_request(payload)

def example_polymorphic_types():
    """Example: Multiple variables with different types."""
    
    print("\n" + "="*70)
    print("Example 3: Multiple polymorphic variables")
    print("="*70)
    
    payload = {
        "constraints": [
            "obj1.<ref> instanceof Ljava/util/List;",
            "obj2.<ref> instanceof Ljava/lang/String;",
            "obj1.<ref> != null",
            "obj2.<ref> != null"
        ],
        "valuation": {
            "obj1.<ref>": 100,
            "obj2.<ref>": 200
        },
        "type_hierarchy": {
            "obj1": """Type: java.util.ArrayList (signature: Ljava/util/ArrayList;)
  Extends: java.util.AbstractList
  Implements: java.util.List, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable
  Class hierarchy: java.util.ArrayList -> java.util.AbstractList -> java.util.AbstractCollection -> java.lang.Object
  All interfaces: java.util.List, java.util.Collection, java.lang.Iterable, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable""",
            "obj2": """Type: java.lang.String (signature: Ljava/lang/String;)
  Extends: java.lang.Object
  Implements: java.io.Serializable, java.lang.Comparable, java.lang.CharSequence
  Class hierarchy: java.lang.String -> java.lang.Object
  All interfaces: java.io.Serializable, java.lang.Comparable, java.lang.CharSequence"""
        }
    }
    
    print("\nPayload:")
    print(json.dumps(payload, indent=2))
    
    send_request(payload)

def send_request(payload):
    """Send request to the solver and display result."""
    
    url = "http://127.0.0.1:8000/solve"
    
    try:
        response = requests.post(url, json=payload, timeout=30)
        
        print(f"\nStatus: {response.status_code}")
        
        if response.status_code == 200:
            result = response.json()
            print("\nResult:")
            print(json.dumps(result, indent=2))
            
            if result.get("result") == "SAT":
                print("\n✓ Constraints are SATISFIABLE")
            elif result.get("result") == "UNSAT":
                print("\n✗ Constraints are UNSATISFIABLE")
            else:
                print(f"\n? Result: {result.get('result')}")
        else:
            print(f"\n✗ HTTP Error: {response.status_code}")
            print(response.text)
            
    except requests.exceptions.ConnectionError:
        print("\n✗ Connection failed!")
        print("Make sure the LLM service is running:")
        print("  cd llm_service")
        print("  uvicorn app:app --reload")
    except Exception as e:
        print(f"\n✗ Error: {e}")

def main():
    print("""
╔══════════════════════════════════════════════════════════════════════╗
║  Type Hierarchy Information - Usage Examples                         ║
╚══════════════════════════════════════════════════════════════════════╝

This script demonstrates how to send type hierarchy information to the
LLM solver. In practice, the Java side (LLMEnhancedSolverContext) does
this automatically.

The type hierarchy helps the LLM understand:
- Inheritance relationships (which classes extend which)
- Interface implementations (which interfaces a class implements)
- Type compatibility (for instanceof and type casting)
- Valid type assignments for reference variables

""")
    
    example_with_arraylist()
    example_with_null_constraint()
    example_polymorphic_types()
    
    print("\n" + "="*70)
    print("Examples completed")
    print("="*70)

if __name__ == "__main__":
    main()
