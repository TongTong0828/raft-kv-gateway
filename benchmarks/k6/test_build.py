"""Pure checks of the generator provenance contract, without Docker or builds."""
import copy
import unittest

import build


class ProvenanceTest(unittest.TestCase):
    def fixture(self, architecture='arm64'):
        patch_sha, individual = build.patch_info()
        binary_sha = 'a' * 64
        labels = {'io.raftkv.k6.upstream.commit': build.PIN['upstreamCommit'],
                  'io.raftkv.k6.source.sha256': build.PIN['sourceSha256'],
                  'io.raftkv.k6.patch.sha256': patch_sha,
                  'io.raftkv.k6.binary.sha256': binary_sha,
                  'io.raftkv.k6.clock': build.PIN['clock'],
                  'io.raftkv.k6.build-origin': build.PIN['buildOrigin'],
                  'io.raftkv.k6.platform': 'linux/' + architecture}
        info = {'Os': 'linux', 'Architecture': architecture, 'Config': {'Labels': labels}}
        manifest = {**copy.deepcopy(build.PIN), 'platform': 'linux/' + architecture,
                    'patchSha256': patch_sha, 'patchSha256ByFile': individual,
                    'binarySha256': binary_sha}
        return info, manifest, binary_sha

    def test_native_arm_and_amd_are_accepted(self):
        for architecture in ['arm64', 'amd64']:
            with self.subTest(architecture=architecture):
                try:
                    build.validate_provenance(*self.fixture(architecture))
                    accepted = True
                except ValueError:
                    accepted = False
                self.assertTrue(accepted)

    def test_wrong_architecture_is_rejected(self):
        info, manifest, binary = self.fixture()
        info['Architecture'] = 's390x'
        with self.assertRaises(ValueError):
            build.validate_provenance(info, manifest, binary)

    def test_selected_platform_must_match_actual_image(self):
        with self.assertRaises(ValueError):
            build.validate_provenance(*self.fixture('arm64'), expected_platform='linux/amd64')

    def test_platform_label_must_match_actual_image(self):
        info, manifest, binary = self.fixture()
        info['Config']['Labels']['io.raftkv.k6.platform'] = 'linux/amd64'
        with self.assertRaises(ValueError):
            build.validate_provenance(info, manifest, binary)

    def test_wrong_platform_manifest_is_rejected(self):
        info, manifest, binary = self.fixture()
        manifest['platform'] = 'linux/amd64'
        with self.assertRaises(ValueError):
            build.validate_provenance(info, manifest, binary)

    def test_wrong_source_is_rejected(self):
        for location in ['label', 'manifest']:
            info, manifest, binary = self.fixture()
            if location == 'label':
                info['Config']['Labels']['io.raftkv.k6.source.sha256'] = 'b' * 64
            else:
                manifest['sourceSha256'] = 'b' * 64
            with self.subTest(location=location), self.assertRaises(ValueError):
                build.validate_provenance(info, manifest, binary)

    def test_wrong_patch_is_rejected(self):
        for location in ['label', 'manifest']:
            info, manifest, binary = self.fixture()
            if location == 'label':
                info['Config']['Labels']['io.raftkv.k6.patch.sha256'] = 'b' * 64
            else:
                manifest['patchSha256'] = 'b' * 64
            with self.subTest(location=location), self.assertRaises(ValueError):
                build.validate_provenance(info, manifest, binary)

    def test_wrong_binary_is_rejected(self):
        info, manifest, _ = self.fixture()
        with self.assertRaises(ValueError):
            build.validate_provenance(info, manifest, 'b' * 64)

    def test_wrong_individual_patch_checksum_is_rejected(self):
        info, manifest, binary = self.fixture()
        manifest['patchSha256ByFile'][build.PIN['patches'][0]] = 'b' * 64
        with self.assertRaises(ValueError):
            build.validate_provenance(info, manifest, binary)

    def test_missing_labels_are_rejected(self):
        info, manifest, binary = self.fixture()
        info['Config']['Labels'] = None
        with self.assertRaises(ValueError):
            build.validate_provenance(info, manifest, binary)


if __name__ == '__main__':
    unittest.main()
