"""
Configuration settings for the LLM service.
"""

import os
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()

# OpenAI API configuration
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
if not OPENAI_API_KEY:
    raise RuntimeError("Missing OPENAI_API_KEY environment variable. Please set it in .env file.")

# LLM_MODEL = "claude-opus-4-5-20251101-thinking-c"  # Default model name
# LLM_MODEL = "claude-sonnet-4-5-20250929"  # Default model name
LLM_MODEL = "deepseek-chat"  # Default model name

# BASE_URL = "https://globalai.vip/v1"  # Optional: Set to a custom base URL (e.g., "https://api.openai.com/v1" or proxy URL)
# BASE_URL = "https://api.chatanywhere.tech/v1"  # Optional: Set to a custom base URL (e.g., "https://api.openai.com/v1" or proxy URL)
BASE_URL = "https://api.deepseek.com/v1"  # Optional: Set to a custom base URL (e.g., "https://api.openai.com/v1" or proxy URL)

