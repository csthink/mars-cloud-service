"""Helpers for the observability acceptance: read structured logs, query the trace and log backends."""
import base64
import json
import pathlib
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def structured_records(path):
    """Yield (raw text, parsed object) for each structured log line of one process, skipping plain output."""
    for raw in pathlib.Path(path).read_text(errors='replace').splitlines():
        raw = raw.strip()
        if not raw.startswith('{'):
            continue
        try:
            yield raw, json.loads(raw)
        except ValueError:
            continue


def structured_lines(path):
    """Yield the parsed structured log lines of one process."""
    for _, line in structured_records(path):
        yield line


def trace_ids(path):
    return {line['traceId'] for line in structured_lines(path) if line.get('traceId')}


def command_trace_id(paths):
    """Print the single trace identifier that appears in every process log."""
    shared = None
    for path in paths:
        current = trace_ids(path)
        shared = current if shared is None else (shared & current)
    print(sorted(shared)[0] if shared else '')
    return 0


def require_trace_id(trace_id):
    """Checks keyed on the trace identifier must refuse an empty one: it matches every line that has none."""
    if trace_id:
        return True
    print('没有链路标识：前一步没有找到三份日志共同的链路标识，这一步无法核对', file=sys.stderr)
    return False


def command_log_fields(trace_id, paths):
    """Every process must have a line carrying that trace, and their span identifiers must differ."""
    if not require_trace_id(trace_id):
        return 1
    spans = []
    for path in paths:
        matching = [line for line in structured_lines(path) if line.get('traceId') == trace_id]
        if not matching:
            print(f'{path} 没有带该链路标识的结构化行', file=sys.stderr)
            return 1
        line = matching[0]
        for field in ('spanId', 'message'):
            if not line.get(field):
                print(f'{path} 的结构化行缺少 {field}', file=sys.stderr)
                return 1
        if not line.get('service', {}).get('name'):
            print(f'{path} 的结构化行缺少服务名', file=sys.stderr)
            return 1
        spans.append({candidate['spanId'] for candidate in matching})
    for index, current in enumerate(spans):
        for other in spans[index + 1:]:
            if current & other:
                print('两个进程写出了相同的 span 标识', file=sys.stderr)
                return 1
    return 0


def fetch(url, headers=None, data=None, timeout=20):
    request = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, response.read()


def command_trace(query_base, trace_id, expected):
    """The trace backend must return one trace whose processes cover every expected service."""
    if not require_trace_id(trace_id):
        return 1
    # 导出器每 5 秒送一批，追踪后端收到之前按链路标识查询得到 404，这时继续重试。
    for attempt in range(15):
        try:
            status, body = fetch(f'{query_base}/api/traces/{trace_id}')
        except urllib.error.HTTPError as error:
            if error.code != 404:
                print(f'查询追踪后端失败：{error}', file=sys.stderr)
                return 1
            status, body = 404, b''
        except urllib.error.URLError as error:
            print(f'查询追踪后端失败：{error}', file=sys.stderr)
            return 1
        if status == 200:
            payload = json.loads(body)
            traces = payload.get('data') or []
            if traces:
                names = {process.get('serviceName') for process in traces[0].get('processes', {}).values()}
                missing = set(expected) - names
                if not missing:
                    return 0
                print(f'第 {attempt + 1} 次查询缺少 {sorted(missing)}', file=sys.stderr)
        time.sleep(3)
    print('追踪后端在重试后仍未返回完整的链路', file=sys.stderr)
    return 1


def command_push_logs(loki_base, run_id, specs):
    """Push the structured lines of each process with labels the query step can select on.

    Lines are pushed exactly as the process wrote them, the way a log collector forwards stdout:
    re-serialising them would change the text that the Grafana log-to-trace field is matched against.
    """
    streams = []
    for spec in specs:
        service, path = spec.split(':', 1)
        values = []
        base = time.time_ns()
        for offset, (raw, _) in enumerate(structured_records(path)):
            values.append([str(base + offset), raw])
        if not values:
            print(f'{path} 没有结构化行可推送', file=sys.stderr)
            return 1
        streams.append({'stream': {'job': 'verify-observability-e2e', 'run_id': run_id,
                                   'service_name': service}, 'values': values})
    payload = json.dumps({'streams': streams}).encode()
    try:
        status, _ = fetch(f'{loki_base}/loki/api/v1/push', {'Content-Type': 'application/json'}, payload)
    except urllib.error.HTTPError as error:
        print(f'推送日志失败：{error.code} {error.read()[:200]}', file=sys.stderr)
        return 1
    return 0 if status in (200, 204) else 1


def command_query_logs(loki_base, run_id, trace_id, expected):
    """Selecting by the trace identifier must return the lines of every expected service."""
    if not require_trace_id(trace_id):
        return 1
    query = '{job="verify-observability-e2e", run_id="%s"} | json | traceId="%s"' % (run_id, trace_id)
    url = f'{loki_base}/loki/api/v1/query_range?' + urllib.parse.urlencode({
        'query': query, 'limit': '500', 'since': '10m'})
    for _ in range(10):
        try:
            status, body = fetch(url)
        except urllib.error.URLError as error:
            print(f'查询日志后端失败：{error}', file=sys.stderr)
            return 1
        if status == 200:
            result = json.loads(body).get('data', {}).get('result', [])
            names = {stream.get('stream', {}).get('service_name') for stream in result}
            if not set(expected) - names:
                return 0
        time.sleep(3)
    print('日志后端按链路标识查不回全部服务', file=sys.stderr)
    return 1


def command_trace_link(grafana_base, trace_id, paths):
    """Grafana must turn the trace identifier in every process's log lines into a link to that trace.

    Grafana applies the field in the browser, so the check covers its three parts instead: the Loki
    data source has one field pointing at the Jaeger data source; the link value is the matched text
    itself, not emptied by the provisioning file's variable expansion; and the pattern extracts this
    run's trace identifier from the lines each process actually wrote.
    """
    if not require_trace_id(trace_id):
        return 1
    try:
        _, loki = fetch(f'{grafana_base}/api/datasources/uid/loki')
        _, jaeger = fetch(f'{grafana_base}/api/datasources/uid/jaeger')
    except urllib.error.URLError as error:
        print(f'读取 Grafana 数据源失败：{error}', file=sys.stderr)
        return 1
    jaeger_uid = json.loads(jaeger)['uid']
    fields = [field for field in json.loads(loki).get('jsonData', {}).get('derivedFields', [])
              if field.get('datasourceUid') == jaeger_uid]
    if len(fields) != 1:
        print(f'Loki 数据源指向 Jaeger 的关联字段应有一个，实际 {len(fields)} 个', file=sys.stderr)
        return 1
    field = fields[0]
    if field.get('url') != '${__value.raw}':
        print(f'关联字段的链接值应为 ${{__value.raw}}，实际是 {field.get("url")!r}', file=sys.stderr)
        return 1
    if field.get('matcherType', 'regex') != 'regex':
        print('关联字段应按正则匹配日志原文：按标签匹配时，查询不写 | json 就没有链接', file=sys.stderr)
        return 1
    pattern = re.compile(field['matcherRegex'])
    for path in paths:
        extracted = {match.group(1) for raw, _ in structured_records(path)
                     for match in [pattern.search(raw)] if match}
        if trace_id not in extracted:
            print(f'{path} 里没有一行能被关联字段取出本次的链路标识', file=sys.stderr)
            return 1
    return 0


def command_applications(monitor_base, user, password, expected):
    """The monitor must list every expected application with an UP status."""
    token = base64.b64encode(f'{user}:{password}'.encode()).decode()
    headers = {'Authorization': 'Basic ' + token, 'Accept': 'application/json'}
    for _ in range(20):
        try:
            status, body = fetch(f'{monitor_base}/applications', headers)
        except urllib.error.URLError as error:
            print(f'读取监控面板失败：{error}', file=sys.stderr)
            return 1
        if status == 200:
            applications = json.loads(body)
            up = {item.get('name') for item in applications
                  if item.get('status') == 'UP'}
            if not set(expected) - up:
                return 0
        time.sleep(3)
    print('监控面板没有列出全部实例', file=sys.stderr)
    return 1


def command_instance_ids(monitor_base, user, password, name):
    """Print the monitor's instance ids of one application, one per line."""
    token = base64.b64encode(f'{user}:{password}'.encode()).decode()
    headers = {'Authorization': 'Basic ' + token, 'Accept': 'application/json'}
    try:
        status, body = fetch(f'{monitor_base}/applications', headers)
    except urllib.error.URLError as error:
        print(f'读取监控面板失败：{error}', file=sys.stderr)
        return 1
    if status != 200:
        print(f'读取监控面板返回 {status}', file=sys.stderr)
        return 1
    ids = []
    for application in json.loads(body):
        if application.get('name') != name:
            continue
        for instance in application.get('instances', []):
            identifier = instance.get('id')
            if isinstance(identifier, dict):
                identifier = identifier.get('value')
            if identifier:
                ids.append(identifier)
    if not ids:
        print(f'监控面板里没有 {name} 的实例', file=sys.stderr)
        return 1
    print('\n'.join(ids))
    return 0


def outbound_address():
    """The IPv4 address this host uses for outbound traffic; connecting a UDP socket sends no packet."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(('192.0.2.1', 9))  # TEST-NET-1 (RFC 5737), never routed to a real host
            address = probe.getsockname()[0]
    except OSError:
        return None
    return None if address.startswith('127.') or address == '0.0.0.0' else address


def reachable(address, port):
    with socket.socket() as probe:
        probe.settimeout(2)
        return probe.connect_ex((address, port)) == 0


def command_loopback_only(ports):
    """Fail when any of the ports accepts connections on the host's non-loopback address."""
    address = outbound_address()
    if address is None:
        print('本机没有非回环地址，无法核对绑定地址', file=sys.stderr)
        return 1
    # 对照：本脚本自己在全部网卡上监听的端口必须能从这个地址连上，否则下面的「连不上」说明不了绑定地址。
    with socket.socket() as control:
        control.bind(('0.0.0.0', 0))
        control.listen(1)
        if not reachable(address, control.getsockname()[1]):
            print(f'从 {address} 连不上本机全部网卡上的对照端口，无法核对绑定地址', file=sys.stderr)
            return 1
    exposed = [port for port in ports if reachable(address, int(port))]
    if exposed:
        print(f'端口 {", ".join(exposed)} 可以从非回环地址 {address} 连接', file=sys.stderr)
        return 1
    return 0


COMMANDS = {
    'trace-id': lambda args: command_trace_id(args),
    'log-fields': lambda args: command_log_fields(args[0], args[1:]),
    'trace': lambda args: command_trace(args[0], args[1], args[2:]),
    'push-logs': lambda args: command_push_logs(args[0], args[1], args[2:]),
    'query-logs': lambda args: command_query_logs(args[0], args[1], args[2], args[3:]),
    'trace-link': lambda args: command_trace_link(args[0], args[1], args[2:]),
    'applications': lambda args: command_applications(args[0], args[1], args[2], args[3:]),
    'instance-ids': lambda args: command_instance_ids(args[0], args[1], args[2], args[3]),
    'loopback-only': lambda args: command_loopback_only(args),
}

if __name__ == '__main__':
    name, rest = sys.argv[1], sys.argv[2:]
    raise SystemExit(COMMANDS[name](rest))
