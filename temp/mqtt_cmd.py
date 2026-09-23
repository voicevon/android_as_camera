"""临时调试脚本：向摄像机端发送 MQTT 控制命令"""
import json
import sys
import paho.mqtt.publish as publish

DEVICE_ID = "690e46ea59ae8593"
HOST = "voicevon.vicp.io"
PORT = 1883
AUTH = {"username": "von", "password": "von123456-"}

action = sys.argv[1] if len(sys.argv) > 1 else "start_stream"
params = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
params["action"] = action

publish.single(
    f"camera/{DEVICE_ID}/cmd",
    json.dumps(params),
    hostname=HOST,
    port=PORT,
    auth=AUTH,
    qos=1,
)
print("published:", json.dumps(params))
