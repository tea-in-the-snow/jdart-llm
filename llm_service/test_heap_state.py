"""
Test script for heap state collection feature.
Tests the /solve endpoint with heap_state parameter.
"""

import requests
import json

BASE_URL = "http://127.0.0.1:8000"

def test_heap_state_linked_list_cycle():
    """
    Test case: Linked list cycle detection with heap state.
    The heap state should help LLM understand the cyclic structure.
    """
    print("=" * 80)
    print("Test: Linked List Cycle Detection with Heap State")
    print("=" * 80)
    
    # Simulate a cyclic linked list structure
    # 466 -> 469 -> 486 -> 487 -> 469 (cycle back)
    payload = {
        "constraints": [
            "slow == fast",  # Constraint indicating cycle detection
            "slow != null",
            "fast != null"
        ],
        "valuation": {
            "slow": 469,
            "fast": 469
        },
        "heap_state": {
            "aliases": {
                "head": 466,
                "slow": 469,
                "fast": 469
            },
            "objects": {
                "466": {
                    "class": "ListNode",
                    "fields": {
                        "next": 469,
                        "val": 1
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
                        "next": 487,
                        "val": 3
                    }
                },
                "487": {
                    "class": "ListNode",
                    "fields": {
                        "next": 469,
                        "val": 4
                    }
                }
            }
        }
    }
    
    response = requests.post(f"{BASE_URL}/solve", json=payload)
    print(f"\nStatus Code: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2)}")
    print()

def test_heap_state_no_cycle():
    """
    Test case: Linked list without cycle.
    """
    print("=" * 80)
    print("Test: Linked List Without Cycle")
    print("=" * 80)
    
    # Linear linked list: 466 -> 469 -> 486 -> null
    payload = {
        "constraints": [
            "slow != fast",  # No cycle condition
            "slow != null",
            "fast != null"
        ],
        "valuation": {
            "slow": 469,
            "fast": 486
        },
        "heap_state": {
            "aliases": {
                "head": 466,
                "slow": 469,
                "fast": 486
            },
            "objects": {
                "466": {
                    "class": "ListNode",
                    "fields": {
                        "next": 469,
                        "val": 1
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
                        "next": "null",
                        "val": 3
                    }
                }
            }
        }
    }
    
    response = requests.post(f"{BASE_URL}/solve", json=payload)
    print(f"\nStatus Code: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2)}")
    print()

def test_heap_state_aliasing():
    """
    Test case: Multiple variables pointing to the same object (aliasing).
    """
    print("=" * 80)
    print("Test: Object Aliasing")
    print("=" * 80)
    
    payload = {
        "constraints": [
            "obj1 == obj2",  # Same reference
            "obj1.field == 42"
        ],
        "valuation": {
            "obj1": 100,
            "obj2": 100
        },
        "heap_state": {
            "aliases": {
                "obj1": 100,
                "obj2": 100  # Both point to same object
            },
            "objects": {
                "100": {
                    "class": "MyObject",
                    "fields": {
                        "field": 42,
                        "name": "test"
                    }
                }
            }
        }
    }
    
    response = requests.post(f"{BASE_URL}/solve", json=payload)
    print(f"\nStatus Code: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2)}")
    print()

def test_heap_state_tree_structure():
    """
    Test case: Binary tree structure.
    """
    print("=" * 80)
    print("Test: Binary Tree Structure")
    print("=" * 80)
    
    payload = {
        "constraints": [
            "root != null",
            "root.left != null",
            "root.right != null",
            "root.value > root.left.value",
            "root.value < root.right.value"
        ],
        "valuation": {
            "root": 200
        },
        "heap_state": {
            "aliases": {
                "root": 200
            },
            "objects": {
                "200": {
                    "class": "TreeNode",
                    "fields": {
                        "value": 10,
                        "left": 201,
                        "right": 202
                    }
                },
                "201": {
                    "class": "TreeNode",
                    "fields": {
                        "value": 5,
                        "left": "null",
                        "right": "null"
                    }
                },
                "202": {
                    "class": "TreeNode",
                    "fields": {
                        "value": 15,
                        "left": "null",
                        "right": "null"
                    }
                }
            }
        }
    }
    
    response = requests.post(f"{BASE_URL}/solve", json=payload)
    print(f"\nStatus Code: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2)}")
    print()

def test_backward_compatibility():
    """
    Test case: Ensure backward compatibility (without heap_state).
    """
    print("=" * 80)
    print("Test: Backward Compatibility (no heap_state)")
    print("=" * 80)
    
    payload = {
        "constraints": [
            "x > 0",
            "x < 10"
        ],
        "valuation": {
            "x": 5
        }
    }
    
    response = requests.post(f"{BASE_URL}/solve", json=payload)
    print(f"\nStatus Code: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2)}")
    print()

if __name__ == "__main__":
    print("\n" + "=" * 80)
    print("HEAP STATE COLLECTION TESTS")
    print("=" * 80 + "\n")
    
    try:
        # Test with heap state
        test_heap_state_linked_list_cycle()
        test_heap_state_no_cycle()
        test_heap_state_aliasing()
        test_heap_state_tree_structure()
        
        # Test backward compatibility
        test_backward_compatibility()
        
        print("\n" + "=" * 80)
        print("ALL TESTS COMPLETED")
        print("=" * 80 + "\n")
        
    except requests.exceptions.ConnectionError:
        print("\nError: Could not connect to LLM service.")
        print("Please ensure the service is running at http://127.0.0.1:8000")
        print("Start it with: python app.py")
    except Exception as e:
        print(f"\nError: {e}")
