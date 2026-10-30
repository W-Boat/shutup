import unittest
from verify_apk_certificate import verify_report

class ApksignerOutputTest(unittest.TestCase):
    expected = 'ab' * 32
    other = 'cd' * 32

    def test_legacy_signer_number(self):
        verify_report(f'Signer #1 certificate SHA-256 digest: {self.expected}\n', self.expected)

    def test_sdk_v2_prefix_from_actual_failure(self):
        verify_report(f'Verifies\nV2 Signer: certificate SHA-256 digest: {self.expected}\n', self.expected)

    def test_sdk_qualified_and_repeated_same_identity(self):
        verify_report(f'V3 Signer (minSdkVersion=33): certificate SHA-256 digest: {self.expected}\n'
                      f'V2 Signer: certificate SHA-256 digest: {self.expected}\n', self.expected)

    def test_empty_or_non_certificate_output_is_rejected(self):
        for report in ('', f'V2 Signer: public key SHA-256 digest: {self.expected}'):
            with self.assertRaises(ValueError):
                verify_report(report, self.expected)

    def test_different_certificate_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_report(f'V2 Signer: certificate SHA-256 digest: {self.other}', self.expected)

    def test_extra_signer_with_different_identity_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_report(f'Signer #1 certificate SHA-256 digest: {self.expected}\n'
                          f'Signer #2 certificate SHA-256 digest: {self.other}\n', self.expected)

if __name__ == '__main__':
    unittest.main()
