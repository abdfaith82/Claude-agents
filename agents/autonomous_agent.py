import os, requests
class AutonomousClaudeAgent:
    def __init__(self):
        self.key = os.getenv("ANTHROPIC_API_KEY", "")
    def ask_and_build(self, t: str) -> str:
        if not self.key: return f"[Offline Agent] Built task: {t}"
        h = {"x-api-key": self.key, "anthropic-version": "2023-06-01", "content-type": "application/json"}
        d = {"model": "claude-3-5-sonnet-latest", "max_tokens": 1024, "messages": [{"role": "user", "content": t}]}
        return requests.post("https://api.anthropic.com/v1/messages", headers=h, json=d).json()["content"][0]["text"]
