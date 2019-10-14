'use strict';

const FLOW_COLLECTION           = 'state-conductor-flow';
const FLOW_STATE_PROP_NAME      = 'state-conductor-status';
const FLOW_PROVENANCE_PROP_NAME = 'state-conductor-status-event';
const FLOW_STATUS_WORKING       = 'working';
const FLOW_STATUS_COMPLETE      = 'complete';

const SUPPORTED_STATE_TYPES = ['task', 'succeed', 'fail'];

const sortFn = (prop, dir = 'desc') => ((a, b) => {
  let p1 = a[prop] || 0;
  let p2 = b[prop] || 0;
  return (dir === 'desc') ? p2 - p1 : p1 - p2;
});

const parseSerializedQuery = (serializedQuery) => {
  return cts.query(fn.head(xdmp.fromJsonString(serializedQuery)));
};

/**
 * Gets a flow definition by flowName
 *
 * @param {*} name
 * @returns
 */
function getFlowDocument(name) {
  return fn.head(cts.search(
    cts.andQuery([
      cts.collectionQuery(FLOW_COLLECTION),
      cts.jsonPropertyValueQuery('flowName', name)
    ])
  ));
}

/**
 * Gets all flow definition documents
 *
 * @returns
 */
function getFlowDocuments() {
  return fn.collection('state-conductor-flow');
}

/**
 * Gets the flow-conductor-status property for the given flowName
 *
 * @param {*} uri
 * @param {*} flowName
 * @returns
 */
function getFlowStatusProperty(uri, flowName) {
  if (fn.docAvailable(uri)) {
    return xdmp.documentGetProperties(uri, fn.QName('', FLOW_STATE_PROP_NAME))
      .toArray()
      .filter(prop => prop.getAttributeNode('flow-name').nodeValue === flowName)[0];
  }
}

/**
 * Determines if this document is being processed by any state conductor flow
 * <state-conductor-status flow="flow-name" state="state-name">flow-status</state-conductor-status>
 * @param {*} uri
 * @returns
 */
function isDocumentInProcess(uri) {
  return cts.contains(
    xdmp.documentProperties(uri), 
    cts.elementValueQuery(fn.QName('', FLOW_STATE_PROP_NAME), FLOW_STATUS_WORKING)
  );
}


/**
 * Gets the flowName for each flow currently processing this document
 *
 * @param {*} uri
 * @returns
 */
function getInProcessFlows(uri) {
  if (fn.docAvailable(uri)) {
    return xdmp.documentGetProperties(uri, fn.QName('', FLOW_STATE_PROP_NAME))
      .toArray()
      .filter(prop => prop.firstChild.nodeValue === FLOW_STATUS_WORKING)
      .map(prop => prop.getAttributeNode('flow-name').nodeValue);
  }
}

/**
 * Sets this document's flow status for the given flow
 *
 * @param {*} uri
 * @param {*} flowName
 * @param {*} stateName
 * @param {*} status
 */
function setFlowStatus(uri, flowName, stateName, status = FLOW_STATUS_WORKING) {
  declareUpdate();
  const existingStatusProp = getFlowStatusProperty(uri, flowName);
  const builder = new NodeBuilder();
  builder.startElement(FLOW_STATE_PROP_NAME);
  builder.addAttribute('flow-name', flowName);
  builder.addAttribute('state-name', stateName);
  builder.addText(status);
  builder.endElement();
  let statusElem = builder.toNode();
  
  if (existingStatusProp) {
    // replace the exsting property
    xdmp.nodeReplace(existingStatusProp, statusElem);
  } else {
    // insert the new property
    xdmp.documentAddProperties(uri, [statusElem]);
  }
}

function addProvenanceEvent(uri, flowName, currState = '', nextState = '') {
  const builder = new NodeBuilder();
  builder.startElement(FLOW_PROVENANCE_PROP_NAME);
  builder.addAttribute('date', (new Date()).toISOString());
  builder.addAttribute('flow-name', flowName);
  builder.addAttribute('from', currState);
  builder.addAttribute('to', nextState);
  builder.endElement();
  let newEvent = builder.toNode();
  xdmp.documentAddProperties(uri, [newEvent]);
}

/**
 * Gets the document's state in the given flow
 *
 * @param {*} uri
 * @param {*} flowName
 * @returns
 */
function getFlowState(uri, flowName) {
  const statusProp = getFlowStatusProperty(uri, flowName);
  return statusProp ? statusProp.getAttributeNode('state-name').nodeValue : null;
}

/**
 * Gets the document's status in the given flow
 *
 * @param {*} uri
 * @param {*} flowName
 * @returns
 */
function getFlowStatus(uri, flowName) {
  const statusProp = getFlowStatusProperty(uri, flowName);
  return statusProp ? statusProp.firstChild.nodeValue : null;
}

/**
 * Determines if the given document matches the given flow's context
 *
 * @param {*} uri
 * @param {*} flow
 * @returns
 */
function checkFlowContext(uri, flow) {
  if (fn.docAvailable(uri)) {
    const query = getFlowContextQuery(flow);
    const uris = cts.uris('', null, cts.andQuery([
      cts.documentQuery(uri),
      query
    ]));
    return uri === fn.string(fn.head(uris));
  }

  return false;
}

/**
 * Given a document's uri, finds all the flows whose context applies,
 * and which have not previously processed this document.
 *
 * @param {*} uri
 * @returns
 */
function getApplicableFlows(uri) {
  const flows = getFlowDocuments().toArray().filter(flow => {
    let flowOjb = flow.toObject();
    return !getFlowStatus(uri, flowOjb.flowName) && checkFlowContext(uri, flowOjb);
  });

  return flows;
}


/**
 * Given a flow, generate a cts query for it's context
 *
 * @param {*} {context = []}
 * @returns
 */
function getFlowContextQuery({context = []}) {
  let queries = context.map(ctx => {
    if (ctx.domain === 'collection') {
      return cts.collectionQuery(ctx.value);
    } else if (ctx.domain === 'directory') {
      return cts.directoryQuery(ctx.value, 'infinity');
    } else if (ctx.domain === 'query') {
      return parseSerializedQuery(ctx.value);
    }
    return cts.falseQuery();
  });

  if (queries.length > 1) {
    queries = cts.orQuery(queries);
  } else {
    queries = queries.pop();
  }

  return queries;
}


/**
 * Generate a cts query matching the context of all flows
 *
 * @returns
 */
function getAllFlowsContextQuery() {
  let queries = getFlowDocuments().toArray().map(flow => getFlowContextQuery(flow.toObject()));

  if (queries.length === 0) {
    queries = cts.falseQuery();
  } else if (queries.length === 1) {
    queries = queries.pop();
  } else {
    queries = cts.orQuery(queries);
  }

  return queries;
}

/**
 * Given a document, a flow, and the state in that flow, perform all actions for that state.
 *
 * @param {*} uri
 * @param {*} flow
 * @param {*} stateName
 */
function performStateActions(uri, flow, stateName) {
  const state = flow.states.filter(state => state.stateName === stateName)[0];
  if (state) {
    xdmp.log(`executing actions for state: ${stateName}`);
    if (state.actions) {
      state.actions.sort(sortFn('priority')).forEach(action => {
        executeModule(action.actionModule, uri, action.options, flow);
      });
    }    
  } else {
    fn.error(null, 'state not found', Sequence.from([`state "${stateName}" not found in flow`]));
  }
}

function executeModule(modulePath, uri, options, flow) {
  try {
    return xdmp.invoke(modulePath, {
      uri: uri,
      options: options,
      flow: flow
    }, {
      database: xdmp.database(flow.contentDatabase),
      modules: xdmp.database(flow.modulesDatabase),
      isolation: 'same-statement'
    });
  } catch (err) {
    xdmp.log(Sequence.from([`An error occured invoking module "${modulePath}"`, err]), 'error');
  }
}

/**
 * Peforms a state transition for the given flow on the given document.
 * If the document is in a terminal state, it marks the flow as complete.
 * Executes condition modules to determin which state to transition too.
 *
 * @param {*} uri
 * @param {*} flow
 */
function executeStateTransition(uri, flow) {
  const currStateName = getFlowState(uri, flow.flowName);
  xdmp.log(`executing transtions for state: ${currStateName}`);

  if (!inTerminalState(uri, flow)) {
    let currState = flow.states.filter(state => state.stateName === currStateName)[0];  
    let transitions; 
    
    if (currState.transitions && currState.transitions.length > 0) {
      transitions = currState.transitions.sort(sortFn('priority'));
    } else {
      fn.error(null, 'INVALID-STATE-DEFINITION', `no "Next" defined for non-terminal state "${currStateName}"`);
    }    

    // find the target transition
    let target = null;
    transitions.forEach(trans => {
      if (!target) {
        if (trans.conditionModule) {
          let resp = fn.head(executeModule(trans.conditionModule, uri, trans.options, flow));
          target = resp ? trans.Next : null;
        } else {
          target = trans.Next;
        }
      }
    });

    // perform the transition
    if (target) {
      setFlowStatus(uri, flow.flowName, target);
      addProvenanceEvent(uri, flow.flowName, currStateName, target);
    } else {
      fn.error(null, 'No conditional or default transtion performed', Sequence.from([
        `uri:"${uri}"`,
        `flow:"${flow.flowName}"`,
        `state:"${currStateName}"`
      ]));
    }
  } else {
    setFlowStatus(uri, flow.flowName, currStateName, FLOW_STATUS_COMPLETE);
    addProvenanceEvent(uri, flow.flowName, currStateName, 'COMPLETED');
  }
}

/**
 * Determines if the given document is in terminal (final) state for the given flow
 *
 * @param {*} uri
 * @param {*} flow
 * @returns
 */
function inTerminalState(uri, flow) {
  const currStateName = getFlowState(uri, flow.flowName);
  let currState = flow.states.filter(state => state.stateName === currStateName)[0];
  
  if (currState && !SUPPORTED_STATE_TYPES.includes(currState.Type.toLowerCase())) {
    fn.error(null, 'INVALID-STATE-DEFINITION', `unsupported state type: "${currState.Type}"`);
  }
  //return !currState || !currState.transitions || currState.transitions.length === 0;
  return (
    !currState || 
    currState.Type.toLowerCase() === 'succeed' ||
    currState.Type.toLowerCase() === 'fail' ||
    (currState.Type.toLowerCase() === 'task' && currState.End === true)
  );
}


module.exports = {
  addProvenanceEvent,
  checkFlowContext,
  executeStateTransition,
  getApplicableFlows,
  getInProcessFlows,
  getFlowDocument,
  getFlowDocuments,
  getFlowState,
  getFlowStatus,
  inTerminalState,
  isDocumentInProcess,
  performStateActions,
  setFlowStatus,
  getAllFlowsContextQuery,
  getFlowContextQuery
};