#!/usr/bin/env python3
"""Run real Minecraft scenarios over AutoJS 1.0.0's HTTP MCP endpoint.

Python standard library only. Each run retains its own transcript and evidence.
Never generates EULA acceptance, reuses PASS files, or retries mutating evals.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'targets/neoforge-1.21.1'


class Blocked(RuntimeError):
    pass


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def source_manifest():
    paths = [ROOT / 'AGENTS.md', ROOT / 'gradle.properties']
    for folder in ('common/src/main', 'targets/neoforge-1.21.1/src/main', 'scripts/autojs'):
        paths.extend(p for p in (ROOT / folder).rglob('*') if p.is_file())
    paths.extend([ROOT / 'common/build.gradle', TARGET / 'build.gradle', TARGET / 'gradle.properties', Path(__file__)])
    return {p.relative_to(ROOT).as_posix(): sha256(p) for p in sorted(set(paths))}


class AutoJS:
    def __init__(self, endpoint, token, output):
        self.endpoint, self.token, self.output = endpoint, token, output
        self.sequence = 0

    def rpc(self, method, params, timeout=10):
        self.sequence += 1
        request = {'jsonrpc': '2.0', 'id': self.sequence, 'method': method, 'params': params}
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        # No token is ever included in transcripts or command lines.
        wire = urllib.request.Request(self.endpoint, json.dumps(request).encode(), headers)
        response = None
        try:
            with urllib.request.urlopen(wire, timeout=timeout) as stream:
                response = json.load(stream)
        finally:
            with (self.output / 'rpc.jsonl').open('a', encoding='utf-8') as stream:
                stream.write(json.dumps({'request': request, 'response': response}, ensure_ascii=False) + '\n')
        if 'error' in response:
            raise RuntimeError(str(response['error']))
        return response['result']

    def tool(self, name, arguments=None, timeout=10):
        result = self.rpc('tools/call', {'name': name, 'arguments': arguments or {}}, timeout)
        content = '\n'.join(c.get('text', '') for c in result.get('content', []) if c.get('type') == 'text')
        if result.get('isError'):
            raise RuntimeError(content)
        return json.loads(content)

    def evaluate(self, code, side='server', timeout=30):
        result = self.tool('eval_js', {'side': side, 'code': code, 'timeout_ms': timeout * 1000}, timeout + 5)
        if not result.get('ok'):
            raise RuntimeError(str(result))
        # Refused operations can be reported in diagnostics despite ok:true.
        if result.get('diagnostics'):
            raise RuntimeError('AutoJS diagnostics require review: ' + str(result['diagnostics']))
        return json.loads(result['value'])


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def run(args):
    run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:8]
    output = ROOT / 'build/autojs-runs' / run_id
    output.mkdir(parents=True)
    report = {'runId': run_id, 'status': 'BLOCKED', 'profile': args.profile,
              'startedUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'scenarios': [],
              'notRun': ['real client/network multiplayer and latency', 'recursive block callbacks and injected failures',
                         'body change between solve and post-packet consumption',
                         'dynamic Sable/Create material callbacks', 'performance profiling']}
    before = source_manifest()
    write_json(output / 'source-manifest.json', before)
    status = subprocess.run(['git', 'status', '--short'], cwd=ROOT, capture_output=True, text=True)
    (output / 'worktree.txt').write_text(status.stdout, encoding='utf-8')
    process, log, client, owned = None, None, None, False
    try:
        endpoint, token = args.endpoint, os.environ.get('AUTOJS_TOKEN', '')
        if args.launch_server:
            if not args.eula_file:
                raise Blocked('--launch-server requires --eula-file pointing to an already accepted EULA file')
            eula = Path(args.eula_file).resolve()
            if not eula.is_file() or not any(line.strip().lower() == 'eula=true' for line in eula.read_text().splitlines()):
                raise Blocked('No existing EULA acceptance; automation does not accept it for you')
            game = output / 'game'
            (game / 'config').mkdir(parents=True)
            shutil.copyfile(eula, game / 'eula.txt')
            write_json(output / 'eula-provenance.json', {'source': str(eula), 'sha256': sha256(eula)})
            port, token = free_port(), uuid.uuid4().hex
            endpoint = f'http://127.0.0.1:{port}/mcp'
            write_json(game / 'config/autojs.json', {'enabled': True, 'unrestricted': True,
                       'bindAddress': '127.0.0.1', 'port': port, 'token': token,
                       'enableServerSide': True, 'enableClientSide': True,
                       'evalTimeoutMs': 30000, 'maxResultChars': 100000, 'logEval': False})
            (game / 'server.properties').write_text(
                'server-ip=127.0.0.1\nserver-port=0\nonline-mode=true\nlevel-name=autojs-acceptance\n'
                'view-distance=2\nsimulation-distance=2\nspawn-protection=0\n'
                'max-tick-time=60000\nlevel-type=minecraft:flat\ngenerate-structures=false\n'
                'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n', encoding='utf-8')
            wrapper = TARGET / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
            command = [str(wrapper), '-p', str(TARGET), 'build', 'runServer',
                       '-PgeRunDirectory=' + str(game), '-PcompatibilityProfile=' + args.profile,
                       '--console', 'plain', '--no-daemon']
            if args.offline:
                command.append('--offline')
            env = os.environ.copy()
            if args.java_home:
                env['JAVA_HOME'] = str(Path(args.java_home).resolve())
            env['AUTOJS_PORT'] = str(port)
            log = (output / 'launch.log').open('w', encoding='utf-8')
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            report['launchCommand'] = command
        if not endpoint:
            raise Blocked('Provide --endpoint or --launch-server')
        client = AutoJS(endpoint, token, output)
        deadline = time.monotonic() + args.startup_timeout
        print(f'AutoJS run {run_id}; evidence: {output}', flush=True)
        while True:
            if process and process.poll() is not None:
                raise Blocked(f'Game exited before AutoJS became ready (exit {process.returncode}); see launch.log')
            try:
                info = client.tool('status', timeout=3)
                if any(s['side'] == 'server' and s['available'] for s in info['sides']):
                    break
            except urllib.error.HTTPError as error:
                raise Blocked(f'AutoJS HTTP {error.code}; check endpoint and AUTOJS_TOKEN') from error
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            if time.monotonic() >= deadline:
                raise Blocked('Timed out waiting for live AutoJS server side')
            time.sleep(1)
        report['autojsStatus'] = info
        init = client.rpc('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {},
                          'clientInfo': {'name': 'gravityengine-acceptance', 'version': '1'}})
        report['initialize'] = init
        tools = client.rpc('tools/list', {})
        if not {'status', 'eval_js'}.issubset({t['name'] for t in tools['tools']}):
            raise Blocked('Installed AutoJS lacks the required tools')
        if process:
            actual_dir = client.evaluate("JSON.stringify(String(Java.loadClass('java.lang.System').getProperty('user.dir')))")
            if Path(actual_dir).resolve() != game.resolve():
                raise Blocked('Endpoint belongs to another game directory')
            owned = True
        report['artifact'] = {p.name: sha256(p) for p in (TARGET / 'build/libs').glob('*.jar')}
        configuration = {'id': run_id, 'profile': args.profile}
        code = 'var __GE_RUN = ' + json.dumps(configuration) + ';\n' + (ROOT / 'scripts/autojs' / (args.scenario + '.js')).read_text(encoding='utf-8')
        result = client.evaluate(code)
        if result.get('runId') != run_id:
            raise RuntimeError('Result does not belong to this invocation')
        report['scenarios'].append(result)
        report['status'] = 'PASS' if result['status'] == 'PASS' and result['cleanup'] == 'PASS' else 'FAIL'
        print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
        if source_manifest() != before:
            raise RuntimeError('Source changed during acceptance; rerun on one stable tree')
    except Blocked as error:
        report['status'], report['error'] = 'BLOCKED', str(error)
    except Exception as error:
        report['status'], report['error'] = 'FAIL', str(error)
    finally:
        if owned:
            try:
                client.evaluate("AutoJS.server().halt(false); JSON.stringify({stopping:true})", timeout=5)
            except Exception as error:
                report['shutdownNote'] = str(error)
        if process:
            if not owned and process.poll() is None:
                try:
                    process.stdin.write(b'stop\n')
                    process.stdin.flush()
                except (BrokenPipeError, OSError):
                    pass
            try:
                process.wait(timeout=30)
                report['gameExitCode'] = process.returncode
                if process.returncode != 0 and report['status'] == 'PASS':
                    report['status'], report['error'] = 'FAIL', 'Game failed during shutdown'
            except subprocess.TimeoutExpired:
                report['shutdown'] = 'FAIL: forced termination of owned launcher tree; PID ' + str(process.pid)
                report['status'] = 'FAIL'
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True, timeout=10)
                else:
                    process.terminate()
            if process.stdin:
                process.stdin.close()
            if log:
                log.close()
        report['finishedUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(output / 'result.json', report)
        print(f"{report['status']}: {output / 'result.json'}", flush=True)
        if 'error' in report:
            print(report['error'], flush=True)
    return {'PASS': 0, 'FAIL': 1, 'BLOCKED': 2}[report['status']]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--endpoint', help='Existing AutoJS /mcp URL; optional token comes from AUTOJS_TOKEN')
    parser.add_argument('--launch-server', action='store_true', help='Launch an isolated ordinary server and stop it afterwards')
    parser.add_argument('--eula-file', help='Existing user-accepted eula.txt, copied unchanged to the isolated directory')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--profile', choices=['none', 'sable', 'create', 'aeronautics'], default='none')
    parser.add_argument('--scenario', choices=['packet-fall', 'current-format'], default='packet-fall')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--startup-timeout', type=int, default=180)
    sys.exit(run(parser.parse_args()))
