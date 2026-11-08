"""Structural guard for the CI workflows (T-117).

Run: python3 -m unittest discover -s scripts/tests -v   (needs PyYAML)
"""
import pathlib
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
WFDIR = ROOT / ".github" / "workflows"


def load(name):
    text = (WFDIR / name).read_text()
    return text, yaml.safe_load(text)


def triggers(wf):
    return wf.get("on", wf.get(True))  # YAML 1.1 parses `on` as True


def run_text(job):
    return "\n".join(str(s.get("run", "")) for s in job["steps"])


def find_key(node, key):
    if isinstance(node, dict):
        return key in node or any(find_key(v, key) for v in node.values())
    if isinstance(node, list):
        return any(find_key(v, key) for v in node)
    return False


class Gate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text, cls.wf = load("gate.yml")

    def test_is_reusable_only(self):
        self.assertEqual(set(triggers(self.wf)), {"workflow_call"})

    def test_permissions_read_only(self):
        self.assertEqual(self.wf["permissions"], {"contents": "read"})

    def test_steps(self):
        jobs = self.wf["jobs"]
        self.assertEqual(len(jobs), 1)
        job = next(iter(jobs.values()))
        self.assertEqual(job["timeout-minutes"], 20)
        runs = run_text(job)
        for cmd in (
            "mvn -B checkstyle:check",
            "mvn -B test",
            "mvn -B package -DskipTests",
            "docker build",
        ):
            self.assertIn(cmd, runs)
        self.assertLess(runs.index("checkstyle:check"), runs.index("mvn -B test"))
        self.assertLess(runs.index("mvn -B test"), runs.index("package -DskipTests"))
        self.assertLess(runs.index("package -DskipTests"), runs.index("docker build"))
        uses = [s.get("uses", "") for s in job["steps"]]
        self.assertIn("actions/checkout@v4", uses)
        setup = [s for s in job["steps"] if s.get("uses", "").startswith("actions/setup-java@v4")]
        self.assertEqual(len(setup), 1)
        self.assertEqual(setup[0]["with"]["distribution"], "temurin")
        self.assertEqual(str(setup[0]["with"]["java-version"]), "17")
        self.assertEqual(setup[0]["with"]["cache"], "maven")
        self.assertTrue(any(u.startswith("actions/upload-artifact@v4") for u in uses))

    def test_image_not_pushed(self):
        self.assertNotIn("push", run_text(next(iter(self.wf["jobs"].values()))).lower())
        self.assertNotIn("login", self.text.lower())


class Ci(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text, cls.wf = load("ci.yml")

    def test_only_pull_request_to_master(self):
        on = triggers(self.wf)
        self.assertEqual(set(on), {"pull_request"})
        self.assertEqual(on["pull_request"]["branches"], ["master"])

    def test_calls_gate(self):
        jobs = self.wf["jobs"]
        self.assertEqual([j["uses"] for j in jobs.values()], ["./.github/workflows/gate.yml"])

    def test_permissions_and_concurrency(self):
        self.assertEqual(self.wf["permissions"], {"contents": "read"})
        self.assertIs(self.wf["concurrency"]["cancel-in-progress"], True)
        self.assertIn("github.ref", self.wf["concurrency"]["group"])


class Deploy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text, cls.wf = load("deploy.yml")

    def test_gate_job_and_build_needs_it(self):
        jobs = self.wf["jobs"]
        self.assertEqual(jobs["gate"]["uses"], "./.github/workflows/gate.yml")
        self.assertEqual(jobs["build"]["needs"], "gate")
        self.assertNotIn("wait-for-jenkins", jobs)

    def test_permissions_cover_gate(self):
        self.assertEqual(self.wf["permissions"], {"id-token": "write", "contents": "read"})

    def test_base_url(self):
        self.assertEqual(self.wf["env"]["PUBLIC_BASE_URL"], "https://erfeamor.com")


class Repo(unittest.TestCase):
    def test_no_environment_key_anywhere(self):
        # `environment:` changes the OIDC sub claim; the trust matches ref:refs/heads/master.
        for f in WFDIR.glob("*.yml"):
            self.assertFalse(find_key(yaml.safe_load(f.read_text()), "environment"), f.name)

    def test_no_jenkins_in_workflows(self):
        for f in WFDIR.glob("*.yml"):
            self.assertNotIn("jenkins", f.read_text().lower(), f.name)

    def test_jenkinsfile_absent(self):
        self.assertFalse((ROOT / "Jenkinsfile").exists())


if __name__ == "__main__":
    unittest.main()
