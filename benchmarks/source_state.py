"""Source provenance gate used before and after strict measurements."""
import subprocess

def require_clean(root,expected_commit=None):
    def git(*args):
        return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
    commit=git('rev-parse','HEAD')
    if git('status','--porcelain'):
        raise ValueError('acceptance requires clean committed source; commit changes and use an ignored work/ output directory')
    if expected_commit is not None and commit!=expected_commit:
        raise ValueError('acceptance source commit changed during measurement')
    return commit
