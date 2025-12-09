"""
Log generation module for LLM service.
Generates log files in the log directory with request/response information.
"""

import os
from datetime import datetime
from typing import Dict, Any, Optional, List
import json
import glob


LOG_DIR = "log"
_current_session_dir: Optional[str] = None


def ensure_log_dir():
    """Ensure the log directory exists."""
    if not os.path.exists(LOG_DIR):
        os.makedirs(LOG_DIR)


def get_or_create_session_dir(started_time: datetime) -> str:
    """
    Get the current session directory, or create a new one if needed.
    
    Args:
        started_time: Timestamp of the current request
    
    Returns:
        Path to the session directory
    """
    global _current_session_dir
    
    # If we have a current session directory and it exists, use it
    if _current_session_dir and os.path.exists(_current_session_dir):
        return _current_session_dir
    
    # Otherwise, create a new session directory
    ensure_log_dir()
    
    # Get the date part (YYYY-MM-DD)
    date_str = started_time.strftime("%Y-%m-%d")
    
    # Find existing directories for this date
    pattern = os.path.join(LOG_DIR, f"{date_str}-*")
    existing_dirs = glob.glob(pattern)
    
    # Extract numbers from existing directories
    max_num = 0
    for dir_path in existing_dirs:
        if os.path.isdir(dir_path):
            dir_name = os.path.basename(dir_path)
            try:
                # Extract number from pattern like "2025-11-29-1"
                num_str = dir_name.split("-")[-1]
                num = int(num_str)
                if num > max_num:
                    max_num = num
            except (ValueError, IndexError):
                pass
    
    # Create new directory with incremented number
    new_num = max_num + 1
    session_dir = os.path.join(LOG_DIR, f"{date_str}-{new_num}")
    os.makedirs(session_dir, exist_ok=True)
    
    _current_session_dir = session_dir
    return session_dir


def reset_session():
    """Reset the current session, forcing a new session directory on next write_log call."""
    global _current_session_dir
    _current_session_dir = None


def generate_log_filename(timestamp: Optional[datetime] = None, session_dir: Optional[str] = None) -> str:
    """Generate a log filename based on timestamp.
    
    Args:
        timestamp: Datetime to use for filename. If None, uses current time.
        session_dir: Session directory to place the log file in. If None, uses current session.
    
    Returns:
        Log file path
    """
    if timestamp is None:
        timestamp = datetime.now()
    time_str = timestamp.strftime("%Y-%m-%d-%H-%M-%S")
    # Add milliseconds to ensure uniqueness even for rapid successive calls
    milliseconds = timestamp.microsecond // 1000
    
    if session_dir is None:
        session_dir = get_or_create_session_dir(timestamp)
    
    return os.path.join(session_dir, f"{time_str}-{milliseconds:03d}-log.md")


def format_log_entry(
    started_time: datetime,
    ended_time: datetime,
    duration: float,
    request: Dict[str, Any],
    response: Dict[str, Any],
    human_message: Optional[str] = None,
    llm_message: Optional[str] = None,
    conversation_logs: Optional[List[Dict[str, Any]]] = None
) -> str:
    """
    Format a log entry with all the required information.
    
    Args:
        started_time: Start timestamp
        ended_time: End timestamp
        duration: Duration in seconds
        request: Request dictionary
        response: Response dictionary
        human_message: Optional human message sent to LLM
        llm_message: Optional LLM response message
    
    Returns:
        Formatted log string
    """
    started_str = started_time.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]  # Remove last 3 digits for milliseconds
    ended_str = ended_time.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    
    log_lines = [
        f"[StartedTime]{started_str}",
        f"[EndedTime]{ended_str}",
        f"[Duration]{duration:.3f} seconds",
        "[Request]",
        json.dumps(request, indent=4, ensure_ascii=False),
        "[Response]",
        json.dumps(response, indent=4, ensure_ascii=False),
        "[llm_conversation_log]"
    ]
    
    if human_message:
        log_lines.append("[human_message]")
        log_lines.append("```")
        log_lines.append(human_message)
        log_lines.append("```")
    
    if llm_message:
        log_lines.append("[llm_message]")
        log_lines.append("```")
        log_lines.append(llm_message)
        log_lines.append("```")

    if conversation_logs:
        log_lines.append("[AgentConversations]")
        for idx, convo in enumerate(conversation_logs, start=1):
            agent = convo.get("agent", "unknown")
            stage = convo.get("stage", "")
            iteration = convo.get("iteration")

            header_parts = [f"Agent: {agent}"]
            if stage:
                header_parts.append(f"Stage: {stage}")
            if iteration is not None:
                header_parts.append(f"Iteration: {iteration}")

            log_lines.append(f"[Conversation {idx}] " + " | ".join(header_parts))

            for field_name, label in (("system", "[system]"), ("human", "[human]"), ("response", "[response]")):
                content = convo.get(field_name)
                if content:
                    log_lines.append(label)
                    log_lines.append("```")
                    log_lines.append(str(content))
                    log_lines.append("```")

            error = convo.get("error")
            if error:
                log_lines.append("[error]")
                log_lines.append(str(error))
    
    return "\n".join(log_lines)


def write_log(
    request: Dict[str, Any],
    response: Dict[str, Any],
    started_time: datetime,
    ended_time: datetime,
    human_message: Optional[str] = None,
    llm_message: Optional[str] = None,
    conversation_logs: Optional[List[Dict[str, Any]]] = None
) -> str:
    """
    Write a log entry to a file.
    
    Args:
        request: Request dictionary
        response: Response dictionary
        started_time: Start timestamp
        ended_time: End timestamp
        human_message: Optional human message sent to LLM
        llm_message: Optional LLM response message
    
    Returns:
        Path to the generated log file
    """
    ensure_log_dir()
    
    # Get or create session directory
    session_dir = get_or_create_session_dir(started_time)
    
    duration = (ended_time - started_time).total_seconds()
    log_content = format_log_entry(
        started_time=started_time,
        ended_time=ended_time,
        duration=duration,
        request=request,
        response=response,
        human_message=human_message,
        llm_message=llm_message,
        conversation_logs=conversation_logs
    )
    
    log_file = generate_log_filename(started_time, session_dir)
    with open(log_file, "w", encoding="utf-8") as f:
        f.write(log_content)
    
    return log_file

