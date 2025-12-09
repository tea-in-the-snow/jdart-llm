"""
Multi-Agent System for Constraint Solving.

This package provides a modular multi-agent architecture for solving
high-level Java constraints using LLMs.

Architecture:
- SolverAgent: Generates candidate solutions with Chain of Thought reasoning
- VerifierAgent: Validates solutions against constraints and rules
- RefinerAgent: Corrects errors based on Verifier feedback
- MultiAgentOrchestrator: Coordinates the workflow with retry logic

Usage:
    from agents import MultiAgentOrchestrator
    
    orchestrator = MultiAgentOrchestrator(llm=llm, max_retries=2)
    result = orchestrator.solve(constraints=[...])
"""

from .utils import extract_first_json
from .solver_agent import SolverAgent
from .verifier_agent import VerifierAgent
from .refiner_agent import RefinerAgent
from .orchestrator import MultiAgentOrchestrator

__all__ = [
    'extract_first_json',
    'SolverAgent',
    'VerifierAgent',
    'RefinerAgent',
    'MultiAgentOrchestrator',
]

__version__ = '2.0.0'
