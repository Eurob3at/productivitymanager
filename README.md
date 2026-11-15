# productivitymanager
Event scraper from email/canvas/etc onto a GUI

# Dependencies
- pip
- google-genai
- pydantic
- OAuth 2.0 credentials for GMail API
- Gemini API
# CLI Interface
To Setup Gemini API: read -rsp "Gemini API key: " GEMINI_API_KEY; echo; export GEMINI_API_KEY
Copy your API key into the prompt and press enter, the key will not show.

To Setup .venv folder: python3 -m venv .venv && source .venv/bin/activate && python -m pip install --upgrade pip && python -m pip install google-genai pydantic
(Make sure to install pip first if you don't have it)
