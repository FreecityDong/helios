import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('scene_tunnel', Path(__file__).with_name('maintain-scene-tunnel.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def result(stdout=b'', code=0):
    return subprocess.CompletedProcess([], code, stdout=stdout, stderr=b'')


class TunnelTest(unittest.TestCase):
    def test_existing_mapping_does_not_mutate_device(self):
        run = Mock(side_effect=[result(b'device\n'), result(b'host-21 tcp:8787 tcp:8787\n')])
        self.assertEqual('ready', module.ensure_tunnel('known:5555', run=run))
        self.assertEqual(2, run.call_count)

    def test_missing_mapping_is_restored_only_on_selected_device(self):
        run = Mock(side_effect=[result(b'device\n'), result(b''), result(b'8787\n')])
        self.assertEqual('restored', module.ensure_tunnel('known:5555', run=run))
        self.assertEqual(['adb', '-s', 'known:5555', 'reverse', 'tcp:8787', 'tcp:8787'], run.call_args.args[0])

    def test_offline_device_is_not_reported_as_connected(self):
        run = Mock(return_value=result(code=1))
        self.assertEqual('offline', module.ensure_tunnel('known:5555', run=run))
        self.assertEqual(1, run.call_count)

    def test_other_ports_do_not_hide_the_missing_scene_mapping(self):
        run = Mock(side_effect=[result(b'device\n'), result(b'host-21 tcp:8888 tcp:8888\n'), result()])
        self.assertEqual('restored', module.ensure_tunnel('known:5555', run=run))

    def test_failed_restore_is_not_reported_as_success(self):
        run = Mock(side_effect=[result(b'device\n'), result(b''), result(code=1)])
        self.assertEqual('restore_failed', module.ensure_tunnel('known:5555', run=run))

    def test_adb_timeout_has_a_terminal_observation(self):
        run = Mock(side_effect=subprocess.TimeoutExpired('adb', 4))
        self.assertEqual('adb_unavailable', module.ensure_tunnel('known:5555', run=run))


if __name__ == '__main__':
    unittest.main()
