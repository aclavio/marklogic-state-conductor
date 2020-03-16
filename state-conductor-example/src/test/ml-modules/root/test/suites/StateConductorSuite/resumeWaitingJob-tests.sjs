'use strict';

const sc = require('/state-conductor/state-conductor.sjs');
const test = require('/test/test-helper.xqy');

const assertions = [];
let jobDoc, erorr, assertion;

//checks a waiting state working
jobDoc = "/randomUIR/thatis/not/here.json"

erorr = null;

try {
  erorr = sc.resumeWaitingJob(jobDoc);
} catch (e) {
  erorr = e;
}

assertions.push(test.assertEqual("BAD-URI", erorr.name, "check if job doc is there"))

assertions