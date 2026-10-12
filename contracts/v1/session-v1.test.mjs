import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';

const contractDir = path.dirname(fileURLToPath(import.meta.url));
const schema = JSON.parse(readFileSync(path.join(contractDir, 'session-v1.schema.json'), 'utf8'));
const fixtures = JSON.parse(readFileSync(path.join(contractDir, 'session-v1.examples.json'), 'utf8'));

// Dependency-free test-time validator for the exact 2020-12 keyword subset used in
// the contract. The future Koog/JS adapters MUST use a full JSON Schema validator,
// not this intentionally small fixture check. It makes schema/examples regressions
// visible in the standalone Recursive Coder contract CI gate.
function conforms(value, rule) {
  if (rule.$ref) {
    assert.match(rule.$ref, /^#\/\$defs\/[A-Za-z]+$/);
    return conforms(value, schema.$defs[rule.$ref.split('/').at(-1)]);
  }
  if (rule.const !== undefined && value !== rule.const) return false;
  if (rule.enum && !rule.enum.includes(value)) return false;
  if (rule.oneOf && rule.oneOf.filter(part => conforms(value, part)).length !== 1) return false;
  if (rule.anyOf && !rule.anyOf.some(part => conforms(value, part))) return false;
  if (rule.allOf && !rule.allOf.every(part => conforms(value, part))) return false;
  if (rule.if && conforms(value, rule.if) && rule.then && !conforms(value, rule.then)) return false;
  if (rule.not && conforms(value, rule.not)) return false;
  if (rule.type) {
    const actual = value === null ? 'null' :
      Array.isArray(value) ? 'array' :
      Number.isInteger(value) ? 'integer' : typeof value;
    if (actual !== rule.type && !(rule.type === 'number' && actual === 'integer')) return false;
  }
  if (typeof value === 'string') {
    if (rule.minLength !== undefined && value.length < rule.minLength) return false;
    if (rule.maxLength !== undefined && value.length > rule.maxLength) return false;
    if (rule.pattern && !new RegExp(rule.pattern).test(value)) return false;
    if (rule.format === 'date-time' && (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/.test(value) || Number.isNaN(Date.parse(value)))) return false;
  }
  if (typeof value === 'number') {
    if (rule.minimum !== undefined && value < rule.minimum) return false;
    if (rule.maximum !== undefined && value > rule.maximum) return false;
  }
  if (Array.isArray(value)) {
    if (rule.uniqueItems && new Set(value.map(item => JSON.stringify(item))).size !== value.length) return false;
    if (rule.items && !value.every(item => conforms(item, rule.items))) return false;
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    if (rule.required?.some(key => !Object.hasOwn(value, key))) return false;
    for (const [key, item] of Object.entries(value)) {
      let matched = false;
      if (rule.properties && Object.hasOwn(rule.properties, key)) {
        if (!conforms(item, rule.properties[key])) return false;
        matched = true;
      }
      for (const [pattern, constraint] of Object.entries(rule.patternProperties ?? {})) {
        if (new RegExp(pattern).test(key)) {
          if (!conforms(item, constraint)) return false;
          matched = true;
        }
      }
      if (!matched && rule.additionalProperties === false) return false;
      if (!matched && rule.additionalProperties && typeof rule.additionalProperties === 'object' && !conforms(item, rule.additionalProperties)) return false;
    }
  }
  return true;
}

const copy = object => structuredClone(object);
const accepted = name => assert.equal(conforms(fixtures[name], schema), true, `should accept ${name}`);
const rejected = (object, why) => assert.equal(conforms(object, schema), false, why);

test('session v1 JSON schema and seven realistic fixtures stay aligned', () => {
  assert.equal(schema.$schema, 'https://json-schema.org/draft/2020-12/schema');
  assert.equal(schema.oneOf.length, 4);
  for (const name of ['freshRequest', 'resumeRequest', 'successResult', 'failureResult', 'event', 'checkpoint', 'forwardMinorRequest']) accepted(name);
});

test('major mismatch and ambiguous or unknown top-level contract fields fail closed', () => {
  const unknownMajor = copy(fixtures.freshRequest);
  unknownMajor.version = '2.0';
  rejected(unknownMajor, 'unknown major');
  const unknownField = copy(fixtures.freshRequest);
  unknownField.implicitWritePermission = true;
  rejected(unknownField, 'unknown top-level authorization');
  const missing = copy(fixtures.freshRequest);
  delete missing.workspace.baselineSha;
  rejected(missing, 'mandatory repository baseline');
});

test('compatible minor versions use optional namespaced extensions only', () => {
  accepted('forwardMinorRequest');
  const outsideExtension = copy(fixtures.forwardMinorRequest);
  outsideExtension.newFeature = 'unspecified';
  rejected(outsideExtension, 'minor version cannot add unknown top-level fields');
  const invalidNamespace = copy(fixtures.forwardMinorRequest);
  invalidNamespace.extensions.experimental = {};
  rejected(invalidNamespace, 'extensions must be x- namespaced');
});

test('fresh/resume requires immutable reference and a stronger fencing generation', () => {
  const freshWithCheckpoint = copy(fixtures.freshRequest);
  freshWithCheckpoint.resume.checkpoint = fixtures.resumeRequest.resume.checkpoint;
  rejected(freshWithCheckpoint, 'fresh must not consume a previous checkpoint');
  const resumeWithoutCheckpoint = copy(fixtures.resumeRequest);
  resumeWithoutCheckpoint.resume.checkpoint = null;
  rejected(resumeWithoutCheckpoint, 'resume requires hashed reference');
  assert.equal(fixtures.resumeRequest.sessionId, fixtures.freshRequest.sessionId);
  assert.equal(fixtures.resumeRequest.invocation.invocationId, fixtures.freshRequest.invocation.invocationId);
  assert.ok(fixtures.resumeRequest.invocation.attempt > fixtures.freshRequest.invocation.attempt);
  assert.ok(fixtures.resumeRequest.resume.fenceGeneration > fixtures.checkpoint.fenceGeneration);
  assert.equal(fixtures.checkpoint.workspace.baselineSha, fixtures.resumeRequest.workspace.baselineSha);
  assert.equal(fixtures.checkpoint.policySha256, fixtures.resumeRequest.toolPolicy.policySha256);
});

test('terminal states require structured errors on failures and none on success', () => {
  const failedWithoutError = copy(fixtures.failureResult);
  failedWithoutError.error = null;
  rejected(failedWithoutError, 'timeout without explanation');
  const successWithError = copy(fixtures.successResult);
  successWithError.error = fixtures.failureResult.error;
  rejected(successWithError, 'success with failure payload');
  const secretInline = copy(fixtures.freshRequest);
  secretInline.model.credentialRef = 'Bearer secret';
  rejected(secretInline, 'must reference an env variable, not a secret value');
  const pathTraversal = copy(fixtures.freshRequest);
  pathTraversal.outputs.resultPath = '/tmp/recursive-coder/../escape.json';
  rejected(pathTraversal, 'artifact traversal');
});

test('journal correlation, child and tool IDs, checkpoint and results are linkable', () => {
  const {freshRequest, resumeRequest, successResult, checkpoint, event} = fixtures;
  assert.equal(event.sessionId, checkpoint.sessionId);
  assert.equal(event.traceId, successResult.traceId);
  assert.equal(successResult.requestId, resumeRequest.requestId);
  assert.equal(event.causationId, 'evt-demo-4');
  assert.ok(event.sequence > checkpoint.sequence);
  assert.ok(successResult.lastEventSeq >= event.sequence);
  assert.equal(successResult.provenance.fenceGeneration, resumeRequest.resume.fenceGeneration);
  assert.ok(successResult.usage.totalTokens >= successResult.usage.outputTokens);
  assert.equal(checkpoint.status, 'durable');
  assert.equal(checkpoint.lastEventSeq, checkpoint.sequence);
  assert.equal(checkpoint.activeChildSessionIds.length, 1);
  assert.equal(successResult.provenance.baselineSha, freshRequest.workspace.baselineSha);
  const malformed = copy(event);
  malformed.actor.toolExecutionId = 42;
  rejected(malformed, 'tool IDs must be correlated strings or null');
});

test('runtime authorization remains stronger than JSON schema shape checks', () => {
  // These are cross-record invariants (trusted runtime responsibilities), not
  // independent JSON-schema checks. Changing fixture examples must not break
  // the documented model/tool authorization or rollback expectations.
  const {freshRequest, resumeRequest} = fixtures;
  assert.deepEqual(freshRequest.requiredCapabilities, resumeRequest.requiredCapabilities);
  assert.deepEqual(freshRequest.toolPolicy, resumeRequest.toolPolicy);
  assert.equal(freshRequest.model.baseUrl, resumeRequest.model.baseUrl);
  assert.ok(freshRequest.toolPolicy.deniedTools.includes('publish_remote'));
  assert.equal(freshRequest.toolPolicy.mode, 'scoped-write');
});


test('fixture validator fails closed when schema introduces an unsupported keyword', () => {
  // Keep the lightweight validator honest: every JSON Schema keyword used by
  // this schema must be implemented in conforms(), or this test fails and
  // requires updating the validator (or replacing it with full Ajv).
  const supported = new Set([
    '$schema', '$id', '$defs', '$ref', 'title', 'description', 'oneOf', 'anyOf',
    'allOf', 'if', 'then', 'not', 'type', 'required', 'properties',
    'patternProperties', 'additionalProperties', 'const', 'enum', 'minimum',
    'maximum', 'minLength', 'maxLength', 'pattern', 'format',
    'uniqueItems', 'items',
  ]);
  const visit = (node, location = '#') => {
    if (!node || typeof node !== 'object' || Array.isArray(node)) return;
    for (const [keyword, child] of Object.entries(node)) {
      assert.ok(supported.has(keyword), `Unsupported schema keyword ${location}/${keyword}`);
      if (keyword === '$defs' || keyword === 'properties' || keyword === 'patternProperties') {
        for (const [name, subSchema] of Object.entries(child)) visit(subSchema, `${location}/${keyword}/${name}`);
      } else if (['oneOf','anyOf','allOf'].includes(keyword)) {
        child.forEach((part, i) => visit(part, `${location}/${keyword}/${i}`));
      } else if (['if','then','not','items','additionalProperties'].includes(keyword)) {
        visit(child, `${location}/${keyword}`);
      }
    }
  };
  visit(schema);
});

test('session admission does not require any external CI provider', () => {
  assert.equal(fixtures.freshRequest.invocation.source, 'cli');
  const unknownSource = copy(fixtures.freshRequest);
  unknownSource.invocation.source = 'remote-workflow';
  rejected(unknownSource, 'only explicit native invocation modes are supported');
  assert.ok(fixtures.freshRequest.workspace.rootPath.startsWith('/tmp/recursive-coder/'));
  assert.equal(fixtures.freshRequest.workspace.workspaceId, fixtures.checkpoint.workspace.workspaceId);
});
