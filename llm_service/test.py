from fastapi.testclient import TestClient
from app import app
from logger import reset_session

# Create test client
client = TestClient(app)


def test_instanceof():
    """Test instanceof constraint"""
    # Test request data
    request_data = {
        "constraints": [
            "obj(ref) instanceof LCar;"
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
    
    print(f"✓ instanceof test passed: result = {data['result']}")
    if "valuation" in data:
        print(f"  valuation: {data['valuation']}")


if __name__ == "__main__":
    # Reset session to create a new folder for this run
    reset_session()
    
    print("=" * 50)
    print("Starting to test app.py")
    print("=" * 50)
    print()
    
    try:
        test_instanceof()
        
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