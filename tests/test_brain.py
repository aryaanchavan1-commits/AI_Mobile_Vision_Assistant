"""Offline smoke tests for Arynox brain + memory.

Runs with any Python 3, no extra dependencies, no hardware required.
Usage: python tests/test_brain.py
"""

import os
import sys
import tempfile
import traceback

TMP = tempfile.mkdtemp(prefix="arynox_test_")
os.environ["ARYNOX_DIR"] = TMP

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from arynox import brain, config, speech  # noqa: E402

SPOKEN = []


def fake_speak(text, cfg):
    SPOKEN.append(text)


speech.speak = fake_speak


def fail(msg):
    print("FAIL:", msg)
    sys.exit(1)


def main():
    cfg = config.load()
    cfg["gemini_api_key"] = ""
    cfg["wake_word"] = "arynox"
    b = brain.ArynoxBrain(cfg)
    b.mem.forget("%")

    def check(label, condition, detail=""):
        if not condition:
            fail(f"{label} {detail}")

    def spoken(fragment):
        return any(fragment.lower() in s.lower() for s in SPOKEN)

    check("help", b.handle("what can you do") is None)
    check("help-speaks", spoken("remember people and things"))
    SPOKEN.clear()

    check("wake-word", b.handle("Arynox what can you do") is None)
    check("wake-word-help", spoken("remember people and things"))
    check("no-backend", b.handle("tell me a joke") is None)
    check("no-backend-speaks", spoken("no brain yet"))
    SPOKEN.clear()

    check("stop-flag", b.handle("STOP") == brain.STOPPED)
    check("sleep-flag", b.handle("go to sleep") == brain.STOPPED)
    check("stop-speaks", spoken("Stopping"))

    check("empty-list", b.handle("what do you remember") is None)
    check("empty-list-speaks", spoken("empty"))
    SPOKEN.clear()

    b.mem.remember("sam", "person", "tall man with glasses", "")
    b.mem.remember("red notebook", "thing", "red notebook on the table", "")

    check("recall", b.handle("who is sam") is None)
    check("recall-hit", spoken("sam"))
    SPOKEN.clear()

    check("recall-via-do-you", b.handle("do you remember sam") is None)
    check("recall-via-do-you-hit", spoken("sam"))
    SPOKEN.clear()

    check("list", b.handle("what do you remember") is None)
    check("list-hits", spoken("sam") and spoken("notebook"))
    SPOKEN.clear()

    check("forget", b.handle("forget sam") is None)
    check("forget-speaks", spoken("Forgot"))
    SPOKEN.clear()

    check("recall-miss", b.handle("who is sam") is None)
    check("recall-miss-speaks", spoken("don't remember"))
    SPOKEN.clear()

    check("remember-name-fallback", b.handle("remember them") is None)
    check("fallback-name", spoken("this person"))
    SPOKEN.clear()

    b.mem.forget("%")
    print("ALL TESTS PASS")


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        traceback.print_exc()
        sys.exit(1)
