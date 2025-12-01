from fastapi.testclient import TestClient
from app import app
from logger import reset_session

# Create test client
client = TestClient(app)


def test_solve_basic():
    """Test basic /solve endpoint functionality"""
    # Test request data
    request_data = {
        "constraints": [
            "x > 0",
            "x < 10"
        ],
        "valuation": None,
        "max_tokens": 256,
        "temperature": 0.0
    }
    
    # Send POST request
    response = client.post("/solve", json=request_data)
    
    # Check response status code
    assert response.status_code == 200, f"Expected 200, got {response.status_code}: {response.text}"
    
    # Check response format
    data = response.json()
    assert "result" in data, "Response should contain 'result' field"
    assert data["result"] in ["SAT", "UNSAT", "UNKNOWN"], f"result should be SAT/UNSAT/UNKNOWN, got {data['result']}"
    
    print(f"✓ Basic test passed: result = {data['result']}")
    if "valuation" in data:
        print(f"  valuation: {data['valuation']}")


def test_solve_with_valuation():
    """Test /solve endpoint with initial valuation"""
    request_data = {
        "constraints": [
            "x + y = 10",
            "x > y"
        ],
        "valuation": {
            "x": 5,
            "y": 3
        },
        "max_tokens": 256,
        "temperature": 0.0
    }
    
    response = client.post("/solve", json=request_data)
    assert response.status_code == 200
    
    data = response.json()
    assert "result" in data
    print(f"✓ Valuation test passed: result = {data['result']}")


def test_solve_minimal():
    """Test minimal request (only required constraints)"""
    request_data = {
        "constraints": ["x = 1"]
    }
    
    response = client.post("/solve", json=request_data)
    assert response.status_code == 200
    
    data = response.json()
    assert "result" in data
    print(f"✓ Minimal request test passed: result = {data['result']}")


def test_solve_empty_constraints():
    """Test empty constraints list"""
    request_data = {
        "constraints": []
    }
    
    response = client.post("/solve", json=request_data)
    # Should still return 200, but result might be UNKNOWN
    assert response.status_code == 200
    
    data = response.json()
    assert "result" in data
    print(f"✓ Empty constraints test passed: result = {data['result']}")


if __name__ == "__main__":
    # Reset session to create a new folder for this run
    reset_session()
    
    print("=" * 50)
    print("Starting to test basic functionality of app.py")
    print("=" * 50)
    print()
    
    try:
        test_solve_basic()
        test_solve_with_valuation()
        test_solve_minimal()
        test_solve_empty_constraints()
        
        print()
        print("=" * 50)
        print("✓ All tests passed!")
        print("=" * 50)
    except Exception as e:
        print()
        print("=" * 50)
        print(f"✗ Test failed: {e}")
        print("=" * 50)
        import traceback
        traceback.print_exc()
        exit(1)

