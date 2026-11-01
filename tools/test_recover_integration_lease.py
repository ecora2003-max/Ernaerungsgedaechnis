import unittest

class RecoveryTests(unittest.TestCase):
    def test_import(self):
        import recover_integration_lease
        self.assertIsNotNone(recover_integration_lease)
