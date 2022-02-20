#!/usr/bin/env bats
#
# Test Apache Http Core.
# Create a reverse proxy to the petclinic server. Check to make sure each of the remote-recording endpoints
# return the appropriate response.

WS_URL=${WS_URL:-http://localhost:9090}

load '../../build/bats/bats-support/load'
load '../../build/bats/bats-assert/load'
load '../helper'

@test "the recording status reports disabled when not recording" {
  run _curl -sXGET "${WS_URL}/_appmap/record"

  assert_success

  assert_json_eq '.enabled' 'false'
}

@test "successfully start a new recording" {
  run _curl -sIXPOST "${WS_URL}/_appmap/record"

  assert_success
  
  echo "${output}" \
    | grep "HTTP/1.1 200"
}

@test "grab a checkpoint during remote recording" {
  start_recording

  _curl -XGET "${WS_URL}"

  run _curl -sXGET "${WS_URL}/_appmap/record/checkpoint"

  assert_json '.classMap'
  assert_json '.events'
  assert_json '.version'

  run _curl -sXGET "${WS_URL}/_appmap/record"

  assert_success
  assert_json_eq '.enabled' 'true'

  run _curl -sXDELETE "${WS_URL}/_appmap/record"

  assert_success

  assert_json '.classMap'
  assert_json '.events'
  assert_json '.version'
}

@test "successfully stop the current recording" {
  start_recording
  
  _curl -XGET "${WS_URL}"
  run _curl -sXDELETE "${WS_URL}/_appmap/record"

  assert_success

  assert_json '.classMap'
  assert_json '.events'
  assert_json '.version'
}

@test "expected appmap captured" {
  start_recording
  
  _curl -XGET "${WS_URL}"
  
  stop_recording

  # Sanity check the events and classmap
  assert_json_eq '.events | length' 6

  
  assert_json_eq '.classMap | length' 1
  assert_json_eq '[.classMap[0] | recurse | .name?] | join(".")' 'org.apache.http.examples.nio.HelloWorldServer$HelloWorldHandler.handle.sayHello'
}
