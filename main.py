import sys
from agents.autonomous_agent import AutonomousClaudeAgent
print(AutonomousClaudeAgent().ask_and_build(" ".join(sys.argv[1:]) or "Ready"))
