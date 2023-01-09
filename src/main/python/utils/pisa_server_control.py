import subprocess
import time
import psutil
import signal


def start_server(jar_path, port, outputfile=None, errorfile=None):
    command = ["java", "-cp", jar_path, f"pisa.server.PisaOneStageServer{port}"]
    outputfile = subprocess.PIPE if outputfile is None else outputfile
    errorfile = subprocess.PIPE if errorfile is None else errorfile

    server_subprocess_id = subprocess.Popen(
        command,
        stdout=outputfile,
        stderr=errorfile,
    ).pid
    time.sleep(5)
    return server_subprocess_id


def close_server(server_subprocess_id):
    # Kill the server and its subprocesses
    try:
        p_process = psutil.Process(server_subprocess_id)
        children = p_process.children(recursive=True)
        for process in children:
            process.send_signal(signal.SIGTERM)
        p_process.send_signal(signal.SIGTERM)
    except psutil.NoSuchProcess:
        pass
