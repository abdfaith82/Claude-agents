import os

class BaseAgent:
    def __init__(self, name="Claude-Architect", role="AI Assistant"):
        self.name = name
        self.role = role

    def execute(self, task: str) -> str:
        return f"[{self.name} - {self.role}] Executing task: {task}"
