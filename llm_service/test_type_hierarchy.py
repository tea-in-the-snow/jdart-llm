#!/usr/bin/env python3
"""
Test script to verify that type_hierarchy information is correctly processed
and sent to the LLM solver.
"""

import requests
import json

def test_type_hierarchy():
    """Test sending type hierarchy information to the solver."""
    
    url = "http://127.0.0.1:8000/solve"
    
    # Sample payload with type hierarchy information
    payload = {
        "constraints": [
            "list(ref) instanceof Ljava/util/List;",
            "list(ref) != null"
        ],
        "valuation": {
            "list(ref)": 123
        },
        "type_hierarchy": {
            "list": """Type: java.util.ArrayList (signature: Ljava/util/ArrayList;)
  Extends: java.util.AbstractList
  Implements: java.util.List, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable
  Class hierarchy: java.util.ArrayList -> java.util.AbstractList -> java.util.AbstractCollection -> java.lang.Object
  All interfaces: java.util.List, java.util.Collection, java.lang.Iterable, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable"""
        }
    }
    
    print("Sending request to LLM solver...")
    print(f"URL: {url}")
    print(f"Payload:\n{json.dumps(payload, indent=2)}\n")
    
    try:
        response = requests.post(url, json=payload, timeout=30)
        
        print(f"Status Code: {response.status_code}")
        print(f"Response:\n{json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200:
            result = response.json()
            if result.get("result") == "SAT":
                print("\n✓ Test PASSED: Constraints are satisfiable")
                print(f"Valuation: {result.get('valuation')}")
            elif result.get("result") == "UNSAT":
                print("\n✓ Test PASSED: Constraints are unsatisfiable (as expected)")
            else:
                print(f"\n? Test result: {result.get('result')}")
        else:
            print(f"\n✗ Test FAILED: HTTP {response.status_code}")
            
    except requests.exceptions.ConnectionError:
        print("\n✗ Connection failed: Is the LLM service running?")
        print("Start it with: uvicorn app:app --reload")
    except Exception as e:
        print(f"\n✗ Test FAILED with exception: {e}")

def test_without_type_hierarchy():
    """Test without type hierarchy information (backward compatibility)."""
    
    url = "http://127.0.0.1:8000/solve"
    
    payload = {
        "constraints": [
            "x > 5",
            "x < 10"
        ],
        "valuation": {
            "x": 3
        }
    }
    
    print("\n" + "="*60)
    print("Testing without type_hierarchy (backward compatibility)...")
    print("="*60)
    print(f"Payload:\n{json.dumps(payload, indent=2)}\n")
    
    try:
        response = requests.post(url, json=payload, timeout=30)
        
        print(f"Status Code: {response.status_code}")
        print(f"Response:\n{json.dumps(response.json(), indent=2)}")
        
        if response.status_code == 200:
            print("\n✓ Backward compatibility test PASSED")
        else:
            print(f"\n✗ Test FAILED: HTTP {response.status_code}")
            
    except Exception as e:
        print(f"\n✗ Test FAILED with exception: {e}")

if __name__ == "__main__":
    print("="*60)
    print("Type Hierarchy Information Test")
    print("="*60)
    test_type_hierarchy()
    test_without_type_hierarchy()
    print("\n" + "="*60)
    print("Tests completed")
    print("="*60)
