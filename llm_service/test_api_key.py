"""
Simple test script to verify if the API key and base URL are valid.
"""

from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage
from config import OPENAI_API_KEY, LLM_MODEL, BASE_URL


def test_api_key_and_base_url():
    """Test if API key and base URL are valid"""
    
    print("=" * 60)
    print("Testing API Key and Base URL")
    print("=" * 60)
    
    # Check configuration
    print(f"\nConfiguration:")
    print(f"  API Key: {OPENAI_API_KEY[:20]}..." if OPENAI_API_KEY else "  API Key: Not set")
    print(f"  Model: {LLM_MODEL}")
    print(f"  Base URL: {BASE_URL if BASE_URL else 'Default (OpenAI)'}")
    
    # Check if API key exists
    if not OPENAI_API_KEY:
        print("\n❌ Error: OPENAI_API_KEY is not configured in config.py")
        return False
    
    # Build LLM parameters
    llm_kwargs = {
        "temperature": 0.0,
        "max_tokens": 50,
        "model": LLM_MODEL,
        "api_key": OPENAI_API_KEY
    }
    
    if BASE_URL:
        llm_kwargs["base_url"] = BASE_URL
        print(f"\nUsing custom Base URL: {BASE_URL}")
    else:
        print(f"\nUsing default Base URL (OpenAI)")
    
    # Create LLM instance
    try:
        print("\nCreating LLM instance...")
        llm = ChatOpenAI(**llm_kwargs)
        print("✓ LLM instance created successfully")
    except Exception as e:
        print(f"\n❌ Failed to create LLM instance: {str(e)}")
        return False
    
    # Send test message
    try:
        print("\nSending test message...")
        test_message = HumanMessage(content="Please reply with 'Test successful'")
        import traceback
        response = llm.invoke([test_message])
        
        text = response.content if hasattr(response, 'content') else str(response)
        print(f"✓ Received response: {text}")
        
        print("\n" + "=" * 60)
        print("✅ Test passed! API Key and Base URL configuration are valid")
        print("=" * 60)
        return True
        
    except Exception as e:
        import traceback
        print(f"\n❌ Failed to send request: {str(e)}")
        print("\nFull error information:")
        traceback.print_exc()
        print("\nPossible reasons:")
        print("  - API Key is invalid or expired")
        print("  - Base URL is incorrect or inaccessible")
        print("  - Network connection issues")
        print("  - Model name is incorrect")
        print("\n" + "=" * 60)
        print("❌ Test failed")
        print("=" * 60)
        return False


if __name__ == "__main__":
    test_api_key_and_base_url()

