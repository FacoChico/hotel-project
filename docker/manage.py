"""Compose initialization, local access, and acceptance checks."""

import argparse
import json
import os
import secrets
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4

LOCAL = Path("/run/local")
ACCESS = LOCAL / "access"
HTTP = build_opener(ProxyHandler({}))


def save_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        os.fchmod(output.fileno(), 0o600)
        json.dump(value, output, indent=2)
        output.write("\n")


def account(role):
    return json.loads((ACCESS / f"compose-{role}-credentials.json").read_text())


def response(path, method="GET", payload=None, token=None, service="api-gateway", port=8080, raw=False):
    raw_response = raw
    headers = {"Accept": "application/json"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode()
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(f"http://{service}:{port}{path}", data=data, headers=headers, method=method)
    try:
        with HTTP.open(request, timeout=15) as reply:
            raw = reply.read()
            return reply.status, (raw.decode() if raw else "") if raw_response else (json.loads(raw) if raw else None)
    except HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def expect(path, status=200, **options):
    actual, body = response(path, **options)
    require(actual == status, f"{options.get('method', 'GET')} {path}: expected {status}, received {actual}")
    return body


def wait_for(description, predicate):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except (URLError, TimeoutError, OSError):
            pass
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for {description}")


def initialize():
    os.umask(0o077)
    directories = [Path("/data/private"), Path("/data/public"), LOCAL, ACCESS, LOCAL / "config", LOCAL / "state"]
    for directory in directories:
        directory.mkdir(parents=True, exist_ok=True)
        directory.chmod(0o700)
    private = Path("/data/private/jwt-private.pem")
    public = Path("/data/public/jwt-public.pem")
    require(private.exists() or not public.exists(), "The existing public key requires its matching private key")
    if not private.exists():
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                        "-out", str(private)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not public.exists():
        subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for role, username in (("admin", "hotel_admin"), ("user", "compose_guest")):
        path = ACCESS / f"compose-{role}-credentials.json"
        if not path.exists():
            save_json(path, {"username": username, "password": secrets.token_urlsafe(24)})
    for name, value in (("username", account("admin")["username"]), ("password", account("admin")["password"])):
        path = LOCAL / "config" / f"security.bootstrap-admin.{name}"
        path.write_text(value)
        path.chmod(0o600)
    for directory in directories:
        os.chown(directory, 10001, 10001)
        for path in directory.rglob("*"):
            os.chown(path, 10001, 10001)
    print("Local JWT keys and account configuration are ready")


def prepare_access(show=True):
    admin_credentials = account("admin")
    wait_for("Booking Service authentication through Gateway", lambda:
             response("/api/v1/users/auth", "POST", admin_credentials)[0] == 200)
    admin = expect("/api/v1/users/auth", method="POST", payload=admin_credentials)
    admin_token = admin["accessToken"]
    require(admin["expiresIn"] == 3600 and admin["tokenType"] == "Bearer", "Unexpected ADMIN token lifetime or type")
    wait_for("Hotel routes through Eureka", lambda:
             response("/api/v1/hotels?size=1", token=admin_token)[0] == 200)
    code, _ = response("/api/v1/users/register", "POST", account("user"))
    require(code in (201, 409), f"Local USER registration returned {code}")
    user = expect("/api/v1/users/auth", method="POST", payload=account("user"))
    require(user["expiresIn"] == 3600 and user["tokenType"] == "Bearer", "Unexpected USER token lifetime or type")
    save_json(ACCESS / "compose-admin-token.json", admin)
    save_json(ACCESS / "compose-user-token.json", user)
    if show:
        prepare_demo(admin_token, user["accessToken"])
        print("Local stack is ready")
        for name, default in (("DISCOVERY", 8761), ("GATEWAY", 8080), ("HOTEL", 8081), ("BOOKING", 8082)):
            port = os.environ.get(name + "_PORT", str(default))
            print(f"{name}: http://localhost:{port}")
            if name in ("HOTEL", "BOOKING"):
                print(f"Swagger: http://localhost:{port}/swagger-ui.html")
                print(f"OpenAPI: http://localhost:{port}/v3/api-docs | /v3/api-docs.yaml")
        print("Access files: docker compose cp booking-service:/run/local/access/. ./secrets")
        print("ADMIN: hotel_admin | USER: compose_guest")
        print("Swagger userBearer: accessToken from the corresponding compose-*-token.json, without 'Bearer'")
        print("User tokens expire after one hour. Refresh: docker compose run --build --rm --no-deps tools access")
        print("API checks: docker compose run --build --rm --no-deps tools check")
        print("All application tests: docker compose run --build --rm tests")
        print("Logs: docker compose logs -f | Stop: docker compose down")
    return admin_token, user["accessToken"]


def prepare_demo(admin, user):
    manifest = LOCAL / "state/demo.json"
    if manifest.exists():
        saved = json.loads(manifest.read_text())
        code, hotel = response(f"/api/v1/hotels/{saved['hotelId']}", token=admin)
        if code == 200 and hotel["name"] == "Demo Hotel":
            print(f"Demo data: hotel {saved['hotelId']}, rooms {saved['roomIds']}, period {saved['period']}")
            return
    hotel = expect("/api/v1/hotels", method="POST", token=admin, status=201,
                   payload={"name": "Demo Hotel", "address": "Central Street, 10"})
    rooms = [expect("/api/v1/rooms", method="POST", token=admin, status=201,
                    payload={"hotelId": hotel["id"], "number": number, "available": available})
             for number, available in (("101", True), ("102", True), ("103", True), ("201", False))]
    start = datetime.now(timezone.utc).date() + timedelta(days=7)
    bookings = []
    for room, offset, cancel in ((rooms[0], 0, False), (rooms[1], 3, True), (rooms[1], 6, True)):
        booking = expect("/api/v1/bookings", method="POST", token=user, status=201,
                         payload={"requestId": str(uuid4()), "autoSelect": False, "roomId": room["id"],
                                  "startDate": (start + timedelta(days=offset)).isoformat(),
                                  "endDate": (start + timedelta(days=offset + 2)).isoformat()})
        if cancel:
            expect(f"/api/v1/bookings/{booking['id']}", method="DELETE", token=user)
        bookings.append(booking["id"])
    period = {"startDate": start.isoformat(), "endDate": (start + timedelta(days=2)).isoformat()}
    save_json(manifest, {"hotelId": hotel["id"], "roomIds": [room["id"] for room in rooms],
                         "bookingIds": bookings, "period": period})
    print(f"Demo data: hotel {hotel['id']}, rooms {[room['id'] for room in rooms]}, period {period}")
    print("Room counters: 101=1, 102=2, 103=0, 201=0 (unavailable). Bookings: one CONFIRMED, two CANCELLED")


def login(role):
    return expect("/api/v1/users/auth", method="POST", payload=account(role))["accessToken"]


def check():
    admin, user = prepare_access(show=False)
    for service, port in (("discovery-server", 8761), ("api-gateway", 8080),
                          ("hotel-service", 8081), ("booking-service", 8082)):
        require(expect("/actuator/health", service=service, port=port)["status"] == "UP", service + " health")
    apps = expect("/eureka/apps", service="discovery-server", port=8761)["applications"]["application"]
    require({"HOTEL-SERVICE", "BOOKING-SERVICE", "API-GATEWAY"}.issubset({app["name"] for app in apps}),
            "Eureka registrations are incomplete")
    print("PASS: all four services are healthy and clients are registered in Eureka")

    expect("/api/v1/hotels", status=401)
    expect("/api/v1/hotels", method="POST", payload={}, token=user, status=403)
    expect("/api/v1/bookings", token=admin, status=403)
    expect("/api/v1/hotels", token="invalid", status=401)
    expect("/api/v1/users/auth", method="POST", payload={**account("user"), "password": secrets.token_urlsafe(24)}, status=401)
    expect("/api/v1/users/register", method="POST", payload={**account("user"), "role": "ADMIN"}, status=400)
    print("PASS: JWT authentication, USER/ADMIN separation, and registration role restrictions")

    credentials = {"username": "review_" + uuid4().hex[:12], "password": secrets.token_urlsafe(24)}
    managed = expect("/api/v1/users", method="POST", payload={**credentials, "role": "USER"}, token=admin, status=201)
    expect(f"/api/v1/users/{managed['id']}", token=admin)
    updated = expect(f"/api/v1/users/{managed['id']}", method="PATCH", payload={"role": "ADMIN"}, token=admin)
    require(updated["role"] == "ADMIN" and "password" not in updated, "User update exposed a password or missed the role")
    expect(f"/api/v1/users/{managed['id']}", method="DELETE", token=admin, status=204)
    expect(f"/api/v1/users/{managed['id']}", token=admin, status=404)
    print("PASS: ADMIN user creation, lookup, update, and deletion")

    hotel = expect("/api/v1/hotels", method="POST", payload={"name": "Review Hotel", "address": "Review Street, 1"},
                   token=admin, status=201)
    expect(f"/api/v1/hotels/{hotel['id']}", method="PATCH", payload={"address": "Review Street, 2"}, token=admin)
    rooms = [expect("/api/v1/rooms", method="POST", payload={"hotelId": hotel["id"], "number": str(number), "available": True},
                    token=admin, status=201) for number in (101, 102, 103, 104)]
    start = datetime.now(timezone.utc).date() + timedelta(days=7)
    end = start + timedelta(days=2)
    period = {"hotelId": hotel["id"], "startDate": start.isoformat(), "endDate": end.isoformat()}
    query = urlencode(period)
    recommended = expect("/api/v1/rooms/recommend?" + query, token=user)
    require([room["id"] for room in recommended["content"]] == [room["id"] for room in rooms],
            "Recommendation ordering did not resolve equal counters by ID")
    expect(f"/api/v1/hotels/{hotel['id']}", method="DELETE", token=admin, status=409)
    print("PASS: hotel/room management and ordered recommendations for available rooms")

    payload = {"requestId": str(uuid4()), "autoSelect": False, "roomId": rooms[0]["id"],
               "startDate": start.isoformat(), "endDate": end.isoformat()}
    first = expect("/api/v1/booking", method="POST", payload=payload, token=user, status=201)
    repeated = expect("/api/v1/bookings", method="POST", payload=payload, token=user)
    require(repeated == first and first["status"] == "CONFIRMED", "Booking repetition changed the result")
    expect("/api/v1/bookings", method="POST", payload={**payload, "roomId": rooms[1]["id"]}, token=user, status=409)
    free = expect("/api/v1/rooms?" + query, token=user)
    require(rooms[0]["id"] not in [room["id"] for room in free["content"]], "Reserved room remained available")
    print("PASS: explicit creation, compatibility alias, idempotency, and date availability")

    duplicate = {**payload, "requestId": str(uuid4()), "roomId": rooms[2]["id"]}
    with ThreadPoolExecutor(max_workers=4) as executor:
        replies = list(executor.map(lambda _: response("/api/v1/bookings", "POST", duplicate, user), range(4)))
    statuses = [code for code, _ in replies]
    require(statuses.count(201) == 1 and all(code in (200, 201, 202) for code in statuses), "Concurrent duplicate creation failed")
    require(expect(f"/api/v1/rooms/{rooms[2]['id']}", token=user)["timesBooked"] == 1, "Duplicate creation changed the counter twice")
    competing = [{**payload, "requestId": str(uuid4()), "roomId": rooms[1]["id"]} for _ in range(2)]
    with ThreadPoolExecutor(max_workers=2) as executor:
        replies = list(executor.map(lambda item: response("/api/v1/bookings", "POST", item, user), competing))
    require(sorted(code for code, _ in replies) == [201, 409], "Overlapping bookings did not select a single winner")
    require(expect(f"/api/v1/rooms/{rooms[1]['id']}", token=user)["timesBooked"] == 1, "Conflict compensation corrupted the counter")
    print("PASS: concurrent duplicates and overlapping reservations preserve one effect")

    automatic = expect("/api/v1/bookings", method="POST", payload={"requestId": str(uuid4()), "autoSelect": True,
                       **period}, token=user, status=201)
    require(automatic["roomId"] == rooms[3]["id"], "Automatic selection did not choose the remaining available room")
    adjacent = {**payload, "requestId": str(uuid4()), "startDate": end.isoformat(), "endDate": (end + timedelta(days=2)).isoformat()}
    expect("/api/v1/bookings", method="POST", payload=adjacent, token=user, status=201)
    statistics = expect("/api/v1/rooms/statistics?" + query, token=admin)
    require(all(row["bookedNights"] == 2 and row["occupancyRate"] == 1.0 for row in statistics["content"]),
            "Hotel statistics did not match actual reservations")
    expect("/api/v1/rooms/statistics?" + query, token=user, status=403)
    expect(f"/api/v1/rooms/{rooms[0]['id']}", method="DELETE", token=admin, status=409)
    expect(f"/api/v1/rooms/{rooms[0]['id']}", method="PATCH", payload={"available": False}, token=admin, status=409)
    print("PASS: automatic selection, adjacent stays, occupancy statistics, and reservation guards")

    other = expect("/api/v1/users/register", method="POST", payload=credentials, status=201)
    other_token = other["token"]["accessToken"]
    expect(f"/api/v1/bookings/{first['id']}", token=other_token, status=404)
    expect(f"/api/v1/bookings/{first['id']}", method="DELETE", token=other_token, status=404)
    history = expect("/api/v1/bookings?size=1", token=user)
    require(history["size"] == 1 and len(history["content"]) == 1 and history["totalElements"] >= 1, "Personal history pagination failed")
    cancelled = expect(f"/api/v1/booking/{first['id']}", method="DELETE", token=user)
    require(cancelled["status"] == "CANCELLED" and not cancelled["releasePending"], "User cancellation was not completed")
    require(expect(f"/api/v1/bookings/{first['id']}", method="DELETE", token=user) == cancelled, "Repeated cancellation changed state")
    require(expect(f"/api/v1/rooms/{rooms[0]['id']}", token=user)["timesBooked"] == 2, "User cancellation reduced historical counts")
    owners = expect("/api/v1/users?size=100", token=admin)["content"]
    owner = next(item for item in owners if item["username"] == account("user")["username"])
    expect(f"/api/v1/users/{owner['id']}", method="DELETE", token=admin, status=409)
    print("PASS: personal history, ownership, cancellation idempotency, and retained history/counters")

    for service, port in (("hotel-service", 8081), ("booking-service", 8082)):
        specification = expect("/v3/api-docs", service=service, port=port)
        require(specification["openapi"].startswith("3."), "OpenAPI version is missing")
        code, body = response("/swagger-ui/index.html", service=service, port=port, raw=True)
        require(code == 200 and "swagger-ui" in body, "Swagger UI is not available")
    expect(f"/api/v1/rooms/{rooms[0]['id']}/release", method="POST", payload={}, token=user,
           service="hotel-service", port=8081, status=401)
    expect(f"/api/v1/rooms/{rooms[0]['id']}/release", method="POST", payload={}, token=user, status=404)
    print("PASS: OpenAPI/Swagger and isolation of internal reservation endpoints")

    outage_room = expect("/api/v1/rooms", method="POST", payload={"hotelId": hotel["id"], "number": "outage", "available": True},
                         token=admin, status=201)
    save_json(LOCAL / "state" / "outage.json", {"roomId": outage_room["id"], "period": period})
    print("All API checks passed. An unused room is ready for the optional pause/recovery check.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "access", "check", "outage", "recovery"))
    command = parser.parse_args().command
    try:
        {"init": initialize, "access": prepare_access, "check": check,
         "outage": check_outage, "recovery": check_recovery}[command]()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"FAILED: {error}", file=sys.stderr)
        return 1
    return 0


def check_outage():
    state = json.loads((LOCAL / "state/outage.json").read_text())
    token = login("user")
    period = state["period"]
    payload = {"requestId": str(uuid4()), "autoSelect": False, "roomId": state["roomId"],
               "startDate": period["startDate"], "endDate": period["endDate"]}
    code, problem = response("/api/v1/bookings", "POST", payload, token)
    require(code in (503, 504) and problem.get("releasePending") is True, "Hotel outage did not retain compensation")
    state["bookingId"] = problem["bookingId"]
    save_json(LOCAL / "state/outage.json", state)
    booking = expect(f"/api/v1/bookings/{state['bookingId']}", token=token)
    require(booking["status"] == "CANCELLED" and booking["releasePending"], "Cancellation was not persisted during the outage")
    print("PASS: unavailable Hotel Service yields a bounded error and a durable compensation task")


def check_recovery():
    state = json.loads((LOCAL / "state/outage.json").read_text())
    token = login("user")
    booking_path = f"/api/v1/bookings/{state['bookingId']}"
    wait_for("background compensation", lambda: expect(booking_path, token=token)["releasePending"] is False)
    require(expect(booking_path, token=token)["status"] == "CANCELLED", "Recovery resurrected a booking")
    wait_for("Hotel Service after unpause", lambda: response(f"/api/v1/rooms/{state['roomId']}", token=token)[0] == 200)
    room = expect(f"/api/v1/rooms/{state['roomId']}", token=token)
    require(room["timesBooked"] == 0, "Compensation did not restore the failed creation counter")
    available = expect("/api/v1/rooms?" + urlencode(state["period"]), token=token)
    require(state["roomId"] in [item["id"] for item in available["content"]], "Compensation did not release the room")
    print("PASS: background recovery frees the room, restores its counter, and retains CANCELLED")


if __name__ == "__main__":
    sys.exit(main())
