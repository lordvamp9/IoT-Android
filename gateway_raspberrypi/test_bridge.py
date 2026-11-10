"""Pruebas locales de validacion; no conectan con AWS ni Firebase."""

import hashlib
import time
import unittest

from aws_firestore_bridge import DEVICE_ID, valid_command, valid_telemetry


class BridgeValidationTest(unittest.TestCase):
    def telemetry(self, temp=24.5, hum=60.2):
        ts = 1794200000
        checksum = hashlib.sha256(
            f"{DEVICE_ID}|{temp:.1f}|{hum:.1f}|{ts}".encode()
        ).hexdigest()[:8]
        return {
            "dispositivoId": DEVICE_ID,
            "temperatura": temp,
            "humedad": hum,
            "timestamp": ts,
            "checksum": checksum,
        }

    def test_valid_and_recovered_telemetry(self):
        data = self.telemetry()
        self.assertTrue(valid_telemetry(data))
        data["origen"] = "offline_cache_resync"
        self.assertTrue(valid_telemetry(data))

    def test_tampered_missing_and_out_of_range_telemetry(self):
        data = self.telemetry()
        data["temperatura"] = 26.5
        self.assertFalse(valid_telemetry(data))
        data = self.telemetry()
        del data["checksum"]
        self.assertFalse(valid_telemetry(data))
        data = self.telemetry(hum=110.0)
        self.assertFalse(valid_telemetry(data))

    def test_command_expiration_and_format(self):
        command = {
            "id": "abc123", "deviceId": DEVICE_ID, "name": "VENT",
            "value": 1, "createdAt": int(time.time()),
        }
        self.assertTrue(valid_command("abc123", command))
        self.assertFalse(valid_command("other", command))
        self.assertFalse(valid_command("abc123", {**command, "value": True}))
        self.assertFalse(valid_command("abc123", {**command, "createdAt": int(time.time()) - 31}))


if __name__ == "__main__":
    unittest.main()
