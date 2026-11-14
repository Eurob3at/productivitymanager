import json
import os
import subprocess
import sys
from typing import List, Optional
from google import genai
from google.genai import types
from pydantic import BaseModel, Field


# ==========================================
# 1. Pydantic Schema for Gemini Output
# ==========================================
class Event(BaseModel):
    title: str = Field(description="Title or name of the event")
    date: str = Field(
        description="Date and time of the event (preferably ISO 8601 like YYYY-MM-DD HH:MM, or readable text)"
    )
    location: Optional[str] = Field(
        default=None,
        description="Location, address, Zoom/Google Meet link, or null if unspecified.",
    )


class EventList(BaseModel):
    events: List[Event] = Field(description="List of all extracted events")


# ==========================================
# 2. Run Gradle to Fetch Emails
# ==========================================
def run_java_fetcher(project_dir="."):
    print(">>> [1/2] Fetching emails via Java/Gradle...", flush=True)

    gradle_executable = "gradlew.bat" if os.name == "nt" else "./gradlew"
    command = [gradle_executable, "run"]

    try:
        # Runs Gradle and streams live output to your terminal
        subprocess.run(command, cwd=project_dir, check=True)
        print(">>> [1/2] Java email fetcher finished successfully!\n", flush=True)
    except subprocess.CalledProcessError as e:
        print(
            f"\n>>> Error: Gradle failed with exit code {e.returncode}",
            flush=True,
        )
        sys.exit(1)
    except FileNotFoundError:
        print(
            f"\n>>> Error: Could not find '{gradle_executable}' in directory '{project_dir}'.",
            flush=True,
        )
        sys.exit(1)


# ==========================================
# 3. Call Gemini to Extract Events
# ==========================================
def extract_events_from_file(input_txt_path: str, output_json_path: str):
    print(">>> [2/2] Parsing emails with Gemini...", flush=True)

    if not os.path.exists(input_txt_path):
        print(
            f">>> Error: Could not find '{input_txt_path}'. Did the Java script create it?",
            flush=True,
        )
        sys.exit(1)

    with open(input_txt_path, "r", encoding="utf-8") as f:
        email_content = f.read()

    # Initialize Gemini client (picks up GEMINI_API_KEY environment variable)
    client = genai.Client()

    prompt = (
        "You are an assistant that analyzes email content and extracts all scheduled events, "
        "meetings, appointments, webinars, and deadlines mentioned in them.\n"
        "Carefully parse the email texts below and extract every unique event."
    )

    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[prompt, email_content],
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=EventList,
            temperature=0.1,
        ),
    )

    events_data = json.loads(response.text)

    with open(output_json_path, "w", encoding="utf-8") as f:
        json.dump(events_data, f, indent=2)

    total_events = len(events_data.get("events", []))
    print(
        f">>> [2/2] Success! Saved {total_events} event(s) to '{output_json_path}'.\n",
        flush=True,
    )


# ==========================================
# Main Execution Pipeline
# ==========================================
if __name__ == "__main__":
    EMAILS_FILE = "emails.txt"
    OUTPUT_FILE = "events.json"

    # Step 1: Run Java to download emails
    run_java_fetcher(project_dir=".")

    # Step 2: Read file & extract with Gemini
    extract_events_from_file(EMAILS_FILE, OUTPUT_FILE)