import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from main import Limits, Settings, create_app, provider_prediction


class ProxyTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.settings = Settings(True, "openai", "chosen-later-for-test", "test-key", "https://api.example.invalid/v1")

        def fake_provider(settings, text):
            self.calls.append((settings.model, text))
            return ["감사합니다"], "", 12

        self.client = TestClient(create_app(self.settings, fake_provider))
        self.headers = {"X-NoTouch-Session": "test-session"}
        self.body = {"text": "ㄱ", "version": 3, "policy_id": self.settings.policy_id}

    def post(self, **changes):
        return self.client.post("/v1/predictions", json=self.body | changes, headers=self.headers)

    def test_disabled_and_incomplete_settings_never_call_provider(self):
        for settings in [Settings(), Settings(True), Settings(True, "openai", "", "key", "https://example.invalid")]:
            client = TestClient(create_app(settings, lambda *_: self.fail("unexpected paid request")))
            self.assertFalse(client.get("/v1/config").json()["enabled"])
            self.assertEqual(503, client.post("/v1/predictions", json=self.body, headers=self.headers).status_code)

    def test_version_is_echoed_and_only_draft_is_passed_to_provider(self):
        response = self.post()
        self.assertEqual(200, response.status_code)
        self.assertEqual(3, response.json()["version"])
        self.assertEqual([("chosen-later-for-test", "ㄱ")], self.calls)
        self.assertEqual("no-store", response.headers["cache-control"])

    def test_consent_does_not_survive_a_provider_or_model_change(self):
        self.assertEqual(412, self.post(policy_id="old-policy").status_code)
        self.assertEqual([], self.calls)
        self.assertNotEqual(self.settings.policy_id,
                            Settings(True, "openai", "different-model", "test-key", self.settings.base_url).policy_id)

    def test_conversation_camera_or_gaze_fields_are_rejected_without_echoing(self):
        for key in ["history", "camera", "gaze", "api_key"]:
            response = self.post(**{key: "private-marker"})
            self.assertEqual(422, response.status_code)
            self.assertNotIn("private-marker", response.text)
        self.assertEqual([], self.calls)

    def test_lengths_types_and_session_are_checked(self):
        for changes in [{"text": "x" * 2001}, {"text": ""}, {"version": -1}, {"version": "3"}]:
            self.assertEqual(422, self.post(**changes).status_code)
        self.assertEqual(400, self.client.post("/v1/predictions", json=self.body).status_code)
        self.assertEqual(413, self.client.post("/v1/predictions", content=b"x" * 17000).status_code)
        self.assertEqual([], self.calls)

    def test_provider_failure_returns_a_generic_error_and_next_request_still_works(self):
        def fail(*_):
            raise TimeoutError("private-text-and-secret-key")
        client = TestClient(create_app(self.settings, fail))
        with self.assertLogs("notouch.metrics", level="INFO") as logs:
            response = client.post("/v1/predictions", json=self.body, headers=self.headers)
        self.assertEqual(503, response.status_code)
        self.assertNotIn("private-text", response.text + str(logs.output))
        self.assertEqual(200, self.post().status_code)

    def test_provider_output_is_bounded(self):
        for value in [(["a", "b", "c"], "", 1), (["contains whitespace"], "", 1), ([], "x" * 161, 1)]:
            client = TestClient(create_app(self.settings, lambda *_: value))
            self.assertEqual(503, client.post("/v1/predictions", json=self.body, headers=self.headers).status_code)

    def test_rate_limit_cannot_be_evaded_by_changing_session_on_the_same_ip(self):
        for _ in range(60):
            self.assertEqual(200, self.post().status_code)
        self.headers["X-NoTouch-Session"] = "different-session"
        self.assertEqual(429, self.post().status_code)
        self.assertEqual(60, len(self.calls))

    def test_env_loader_has_no_implicit_model_and_does_not_execute_values(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", {}, clear=True):
            path = Path(directory) / ".env"
            path.write_text("# model undecided\nAI_PREDICTION_ENABLED=false\nAI_MODEL=\nAI_API_KEY=\n", encoding="utf-8")
            settings = Settings.load(path)
            self.assertEqual("", settings.model)
            self.assertFalse(settings.ready)
            path.write_text("AI_MODEL=$(touch nope)\n", encoding="utf-8")
            self.assertEqual("$(touch nope)", Settings.load(path).model)
            self.assertFalse((Path(directory) / "nope").exists())

    def test_provider_payload_has_no_training_history_storage_or_default_model(self):
        captured = []

        class Response:
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def read(self, _limit):
                return json.dumps({"status": "completed", "output": [{"type": "message", "content": [
                    {"type": "output_text", "text": '{"words":["감사합니다"],"continuation":""}'}]}],
                    "usage": {"total_tokens": 7}}).encode()

        class Opener:
            def open(self, req, timeout):
                captured.append(json.loads(req.data))
                return Response()

        with patch("main.urllib_request.build_opener", return_value=Opener()):
            self.assertEqual((["감사합니다"], "", 7), provider_prediction(self.settings, "ㄱ"))
        payload = captured[0]
        self.assertFalse(payload["store"])
        self.assertEqual(self.settings.model, payload["model"])
        self.assertEqual({"draft": "ㄱ"}, json.loads(payload["input"]))
        self.assertNotIn("previous_response_id", payload)

    def test_concurrent_duplicate_session_is_rejected(self):
        limits = Limits()
        limits.acquire("ip", "session")
        with self.assertRaises(Exception):
            limits.acquire("ip", "session")
        limits.release("ip", "session")
        limits.acquire("ip", "session")


if __name__ == "__main__":
    unittest.main()
