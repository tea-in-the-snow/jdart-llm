"""
Configuration settings for the LLM service.
"""

# OpenAI API configuration
OPENAI_API_KEY = "sk-PQStEiSR6Q5iZmvaqWHWQKjsLXpEUJ45IE9hOAa7BtGIpdyh"  # Replace with your actual API key
# OPENAI_API_KEY = "sk-98eca35277d1449f8c609c3942f2da0d"  # Replace with your actual API key

LLM_MODEL = "claude-sonnet-4-5-20250929"  # Default model name
# LLM_MODEL = "deepseek-reasoner"  # Default model name
# BASE_URL = "https://yinli.one/"  # Optional: Set to a custom base URL (e.g., "https://api.openai.com/v1" or proxy URL)
BASE_URL = "https://api.chatanywhere.tech/v1"  # Optional: Set to a custom base URL (e.g., "https://api.openai.com/v1" or proxy URL)
# BASE_URL = "https://api.deepseek.com/v1"  # Optional: Set to a custom base URL (e.g., "https://api.openai.com/v1" or proxy URL)

