import pathlib,subprocess,tempfile,unittest
try:
    from source_state import require_clean
except ModuleNotFoundError:
    require_clean=None

class SourceStateTest(unittest.TestCase):
    def test_dirty_source_and_changed_commit_are_rejected(self):
        self.assertIsNotNone(require_clean,'clean source guard must exist')
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            def git(*args):
                return subprocess.check_output(['git','-C',d,*args],text=True,stderr=subprocess.DEVNULL).strip()
            git('init');file=root/'script.js';file.write_text('original');git('add','.')
            git('-c','user.name=Test','-c','user.email=test@example.invalid','commit','-m','initial')
            commit=require_clean(root)
            file.write_text('changed')
            with self.assertRaisesRegex(ValueError,'clean committed source'):require_clean(root,commit)
            file.write_text('original');(root/'extra.js').write_text('untracked')
            with self.assertRaisesRegex(ValueError,'clean committed source'):require_clean(root,commit)
            git('add','.');git('-c','user.name=Test','-c','user.email=test@example.invalid','commit','-m','next')
            with self.assertRaisesRegex(ValueError,'source commit changed'):require_clean(root,commit)
            self.assertNotEqual(commit,require_clean(root))
