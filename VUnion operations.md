- TODO: differentiate between minimal diffs (RAM based) and DB-based diffs with redundant version changes.
- TODO: consider an alternative architecture with a single actor with embedded version generator applying updates to DB in ordered way, a flavor of Proxy persistence thread
- TODO: alternative data model - rather than storing normalized data, store diffs in sequence, read them sequentially upon load

# VGraph operations

NB: Those diffs may be simplified for rAM-only use case, but outlined as they should appear in presence of fault-tolerant MySQL/Galera DB (certain updates are redundant to mitigate possible race conditions on Galera side).

Let's define abstract Counter, that produces globally monotonically increasing sequence.

	Counter<V> {
		V getNext();
	}

Counter will be used for version generation.

---

Lets describe operations on VGraph:

## 1. New vertex type, 2 vertexes +links to subgraph0
   Notes: VertexType should be created prior to vertex creation and linked to a subgraph prior to linking a vertex of that type.
   It's also ok to link vertexes and vertex types to a subgraph together in the same transaction.

   Stream:
   {
   "from" : "[]",
   "graphName" : "graph0",

   	"vertexTypes" : [
   		{
   			"elementId" : "1",
   			"version" : "1",
   			"key" : "vertexTypeKey1",
   			"content" : "<sample vertex type>",
   			
   			"vertexTypeName" : "vertexType0"
   		}
   	],
   	
   	"vertexes" : [
   		{
   			"elementId" : "2",
   			"version" : "2",
   			"key" : "vertexKey1",
   			"content" : "<sample vertex content>",
   			
   			"vertexTypeId" : "1"
   		},
   		{
   			"elementId" : "5",
   			"version" : "5",
   			"key" : "vertexKey2",
   			"content" : "<sample vertex content>",
   			
   			"vertexTypeId" : "1"
   		}
   	],
   	
   	"subgraphs" : [
   		{
   			"name" : "subgraph0",
   			"subgraphVersionTo" :  "6",
   			
   			"linkUpdates" : [
   				{
   					"linkId" : "3",
   					"linkUpdate" : {
   						"elementId" : "3",
   						"key" : "linkKey1",
   						"version" : "3",
   						"content" : "<sample link content>",
   						"isTombstone" : false
   					},
   					"linkedElementUpdate" : {
   						"linkedElementId" : "1",
   						"linkedElementVersion" : "1"
   					}
   				},
   				{
   					"linkId" : "4",
   					"linkUpdate" : {
   						"elementId" : "4",
   						"key" : "linkKey2",
   						"version" : "4",
   						"content" : "<sample link content>",
   						"isTombstone" : false
   					},
   					"linkedElementUpdate" : {
   						"linkedElementId" : "2",
   						"linkedElementVersion" : "2"
   					}
   				},
   				{
   					"linkId" : "6",
   					"linkUpdate" : {
   						"elementId" : "6",
   						"key" : "linkKey3",
   						"version" : "6",
   						"content" : "<sample link content>",
   						"isTombstone" : false
   					},
   					"linkedElementUpdate" : {
   						"linkedElementId" : "5",
   						"linkedElementVersion" : "5"
   					}
   				}
   			]
   		}
   	]
   }

---

## 2. New edge type, edge +links to subgraph0
   Notes: EdgeType should be created prior to edge creation and linked to a subgraph prior to linking an edge of that type.

Edge can only be linked if both of its vertexes are linked to subgraph.
It's also ok to link edges, edge types and corresponding vertexes to a subgraph together in the same transaction.

	Stream:
	{
		"from" : "[subgraph0:6]",
		"graphName" : "graph0",
		
		"edgeTypes" : [
			{
				"elementId" : "7",
				"version" : "7",
				"key" : "edgeTypeKey1",
				"content" : "<sample edge type>",
				
				"edgeTypeName" : "edgeType0"
			}
		],
		
		"edges" : [
			{
				"elementId" : "8",
				"version" : "8",
	
				"key" : "edgeKey1",
				"content" : "<sample edge content>",
				
				"edgeTypeId" : "7",
				"vertexFromId" : "2",
				"vertexToId" : "5",
				"isDirected" : false
			}
		],
		
		"subgraphs" : [
			{
				"name" : "subgraph0",
				"subgraphVersionTo" : "10",
				 
				"linkUpdates" : [
					{
						"linkId" : "9",
						"linkUpdate" : {
							"elementId" : "9",
							"key" : "linkKey4",
							"version" : "9",
							"content" : "<sample link content>",
							"isTombstone" : false
						},
						"linkedElementUpdate" : {
							"linkedElementId" : "7",
							"linkedElementVersion" : "7"
						}
					},
					{
						"linkId" : "10",
						"linkUpdate" : {
							"elementId" : "10",
							"key" : "linkKey5",
							"version" : "10",
							"content" : "<sample link content>",
							"isTombstone" : false
						},
						"linkedElementUpdate" : {
							"linkedElementId" : "8",
							"linkedElementVersion" : "8"
						}
					}
				]
			}
		]
	}

---

## 3. New links for existing elements (vertexes, edges, vertex types, edge types)
   Notes: same constraints as in (1) apply to vertex linking, same constraints as in (2) apply to edge linking

In this case we always want to have a linked element in diff's vertexes/edges collection.
Even though such element (if newly added) might have a version behind our graph version, and very well might be already present in the VGraphCache's vertexes or edges, it's hard  to reason about whether it's there or not in presence of subgraph filters.

So it seems to me ATM more viable to just include it every time it's linked to a subgraph than reasoning about whether other subgraphs of a particular client with particular filters may or may not have previously retrieved the current version of said element.

Note how in our example although _subgraph0_ has version 5, and yet we have vertexes {1:v1}, {2:v2} and edge {4:v4} in our diff structure.
Since we know that those elements were previously linked to _subgraph0_, we can reason about them being already in the structure of the client cache, and based on that knowledge avoid sending them the second time. However, if, for example, our client filters out our stream to receive only updates for "subgraph1", the elements would appear new to client's cache and in that case it would be mandatory to have them in vertexes/edges arrays.
It's still possible to reason which elements exactly were filtered out, but it's more challenging and will require creating complicated filter-dependent diff retrieval, so as of now it seems as premature optimisation.
It seems most reasonable to address and optimize it later, during implementation of stream filters.

	Stream:
	{
		"from" : "[subgraph0:10]",
		"graphName" : "graph0",
		
		"vertexTypes" : [
			{
				"elementId" : "1",
				"version" : "1",
				"key" : "vertexTypeKey1",
				"content" : "<sample vertex type>",
				
				"vertexTypeName" : "vertexType0"
			}
		],
		
		"vertexes" : [
			{
				"elementId" : "2",
				"version" : "2",
				"key" : "vertexKey1",
				"content" : "<sample vertex content>",
				
				"vertexTypeId" : "1"
			},
			{
				"elementId" : "5",
				"version" : "5",
				"key" : "vertexKey2",
				"content" : "<sample vertex content>",
				
				"vertexTypeId" : "1"
			}
		],
		
		"edgeTypes" : [
			{
				"elementId" : "7",
				"version" : "7",
				"key" : "edgeTypeKey1",
				"content" : "<sample edge type>",
				
				"edgeTypeName" : "edgeType0"
			}
		],
		
		"edges" : [
			{
				"elementId" : "8",
				"version" : "8",
	
				"key" : "edgeKey1",
				"content" : "<sample edge content>",
				
				"edgeTypeId" : "7",
				"vertexFromId" : "2",
				"vertexToId" : "5",
				"isDirected" : false
			}
		],

		"subgraphs" : [
			{
				"name" : "subgraph1",
				"subgraphVersionTo" : "15",
			
				"linkUpdates" : [
					{
						"linkId" : "11",
						"linkUpdate" : {
							"elementId" : "11",
							"key" : "linkKey6",
							"version" : "11",
							"content" : "<sample link content>",
							"isTombstone" : false
						},
						"linkedElementUpdate" : {
							"linkedElementId" : "1",
							"linkedElementVersion" : "1"
						}
					},
					{
						"linkId" : "12",
						"linkUpdate" : {
							"elementId" : "12",
							"key" : "linkKey7",
							"version" : "12",
							"content" : "<sample link content>",
							"isTombstone" : false
						},
						"linkedElementUpdate" : {
							"linkedElementId" : "2",
							"linkedElementVersion" : "2"
						}
					},
					{
						"linkId" : "13",
						"linkUpdate" : {
							"elementId" : "13",
							"key" : "linkKey8",
							"version" : "13",
							"content" : "<sample link content>",
							"isTombstone" : false
						},
						"linkedElementUpdate" : {
							"linkedElementId" : "5",
							"linkedElementVersion" : "5"
						}
					},
					{
						"linkId" : "14",
						"linkUpdate" : {
							"elementId" : "14",
							"key" : "linkKey9",
							"version" : "14",
							"content" : "<sample link content>",
							"isTombstone" : false
						},
						"linkedElementUpdate" : {
							"linkedElementId" : "7",
							"linkedElementVersion" : "7"
						}
					},
					{
						"linkId" : "15",
						"linkUpdate" : {
							"elementId" : "15",
							"key" : "linkKey10",
							"version" : "15",
							"content" : "<sample link content>",
							"isTombstone" : false
						},
						"linkedElementUpdate" : {
							"linkedElementId" : "8",
							"linkedElementVersion" : "8"
						}
					},
				]
			}
		]
	}

---

## 4. Vertex updated
   All three options - content updated, key updated and type updated should return the whole updated vertex structure, as well as corresponding link updates for all linked subgraphs

   Stream:
   {
   "from" : "[subgraph0:10,subgraph1:15]",
   "graphName" : "graph0",

   	"vertexes" : [
   		{
   			"elementId" : "2",
   			"version" : "16",
   			"key" : "vertexKey1-1",
   			"content" : "<sample vertex content-1>",
   			
   			"vertexTypeId" : "1"
   		}
   	],
   	
   	"subgraphs" : [
   		{
   			"name" : "subgraph0",
   			"subgraphVersionTo" : "16",
   		
   			"linkUpdates" : [
   				{
   					"linkId" : "4",
   					"linkedElementUpdate" : {
   						"linkedElementId" : "2",
   						"linkedElementVersion" : "16"
   					}
   				}
   			]
   		},
   		{
   			"name" : "subgraph1",
   			"subgraphVersionTo" : "16",
   		
   			"linkUpdates" : [
   				{
   					"linkId" : "12",
   					"linkedElementUpdate" : {
   						"linkedElementId" : "2",
   						"linkedElementVersion" : "16"
   					}
   				}
   			]
   		}
   	]
   }

---

## 5. Edge updated
   All options - content updated, key updated, type updated and isDirected updated should return the whole updated edge structure, as well as corresponding link updates for all connected subgraphs
   NB: current vision is that VertexFrom and VertexTo are immutable.

   Stream:
   {
   "from" : "[subgraph0:16,subgraph1:16]",
   "graphName" : "graph0",

   	"edges" : [
   		{
   			"elementId" : "8",
   			"version" : "17",

   			"key" : "edgeKey1-1",
   			"content" : "<sample edge content-1>",
   			
   			"edgeTypeId" : "7",
   			"vertexFromId" : "2",
   			"vertexToId" : "5",
   			"isDirected" : true
   		}
   	],
   	
   	"subgraphs" : [
   		{
   			"name" : "subgraph0",
   			"subgraphVersionTo" : "17",
   		
   			"linkUpdates" : [
   				{
   					"linkId" : "10",
   					"linkedElementUpdate" : {
   						"linkedElementId" : "8",
   						"linkedElementVersion" : "17"
   					}
   				}
   			]
   		},
   		{
   			"name" : "subgraph1",
   			"subgraphVersionTo" : "17",
   		
   			"linkUpdates" : [
   				{
   					"linkId" : "15",
   					"linkedElementUpdate" : {
   						"linkedElementId" : "8",
   						"linkedElementVersion" : "17"
   					}
   				}
   			]
   		}
   	]
   }

---

## 6. Link updated
   Notes:
   a. All options - content updated, key updated and isTombstone updated should return the updated link structure
   NB: current vision is that LinkedElementId is immutable.

b. Marking link as tombstone:
b.1. Vertex link can only be tombstoned if all connected edges are tombstoned.
Logically that refers to the fact that an active (not tombstoned) edge can't point to a tombstoned vertex.

b.2. Vertex type and Edge type.
Vertex type link can only be tombstoned if all vertexes of that type are tombstoned.
Similarly, Edge type link can only be tombstoned if all edges of that type are tombstoned.
Logically that refers to the fact that an active (not tombstoned) vertex/edge can't be of a tombstoned type.

b.3. I.e. tombstone edges first, vertexes after, and then types
It's also ok to tombstone connected edges, vertexes and types together in the same transaction.

c. Untombstone link:
c.1. Edge/vertex links can only be untombstoned if corresponding Edge/Vertex types links are not tombstoned.
Logically that refers to the fact that an active (not tombstoned) vertex/edge can't be of a tombstoned type.

c.2. Edge link can only be untombstoned if no connected vertexes are tombstoned.
Logically that refers to the fact that an active (not tombstoned) edge can't point to a tombstoned vertex.

c.3. I.e. untombstone types first, vertexes after, and then edges (it's also ok to untombstone connected types, vertexes and edges together in the same transaction).

	Stream:
	{
		"from" : "[subgraph0:17,subgraph1:17]",
		"graphName" : "graph0",

		"subgraphs" : [
			{
				"name" : "subgraph0",
				"subgraphVersionTo" : "18",
			
				"linkUpdates" : [
					{
						"linkId" : "10",
						"linkUpdate" : {
							"elementId" : "10",
							"key" : "linkKey5-1",
							"version" : "18",
							"content" : "<sample link content-1>",
							"isTombstone" : false
						}
					}
				]
			}
		]
	}

---

## 7. GraphElement
### 7.1 GraphElement created

   Stream:
   {
   "from" : "[subgraph0:18,subgraph1:17]",
   "graphName" : "graph0",

   	"graphElementRecord" : {
   		"graphElementUpdateVersion" : "19",
   		"graphElement" : {
   			"elementId" : "16",
   			"version" : "19",

   			"key" : "graphElementKey1",
   			"content" : "<sample graph element content>"
   		}
   	}
   }

### 7.2 GraphElement updated

	Stream:
	{
		"from" : "[19,subgraph0:18,subgraph1:17]",
		"graphName" : "graph0",

		"graphElementRecord" : {
			"graphElementUpdateVersion" : "20",
			"graphElement" : {
				"elementId" : "16",
				"version" : "20",

				"key" : "graphElementKey1-1",
				"content" : "<sample graph element content-1>"
			}
		}
	}

---

## 8. SubgraphElement
### 8.1 SubgraphElement created

   Stream:
   {
   "from" : "[20,subgraph0:18,subgraph1:17]",
   "graphName" : "graph0",

   	"subgraphs" : [
   		{
   			"name" : "subgraph0",
   			"subgraphVersionTo" : "21",
   		
   			"subgraphElementRecord" : {
   				"subgraphElementUpdateVersion" : "21",
   				"subgraphElement" : {
   					"elementId" : "17",
   					"version" : "21",

   					"key" : "subgraphElementKey1",
   					"content" : "<sample subgraph element content>"
   				}
   			}
   		}
   	]
   }

### 8.2 SubgraphElement updated

	Stream:
	{
		"from" : "[20,subgraph0:21,subgraph1:17]",
		"graphName" : "graph0",

		"subgraphs" : [
			{
				"name" : "subgraph0",
				"subgraphVersionTo" : "22",
			
				"subgraphElementRecord" : {
					"subgraphElementUpdateVersion" : "22",
					"subgraphElement" : {
						"elementId" : "17",
						"version" : "22",

						"key" : "subgraphElementKey1-1",
						"content" : "<sample subgraph element content-1>"
					}
				}
			}
		]
	}

---

## 9. Vertex Type renamed

   Stream:
   {
   "from" : "[20,subgraph0:22,subgraph1:17]",
   "graphName" : "graph0",

   	"vertexTypes" : [
   		{
   			"elementId" : "1",
   			"version" : "23",
   			"key" : "vertexTypeKey1-1",
   			"content" : "<sample vertex type-1>",
   			
   			"vertexTypeName" : "vertexType0-1"
   		}
   	],
   	
   	"subgraphs" : [
   		{
   			"name" : "subgraph0",
   			"subgraphVersionTo" : "23",
   		
   			"linkUpdates" : [
   				{
   					"linkId" : "3",
   					"linkedElementUpdate" : {
   						"linkedElementId" : "1",
   						"linkedElementVersion" : "23"
   					}
   				},
   			]
   		},
   		{
   			"name" : "subgraph1",
   			"subgraphVersionTo" : "23",
   		
   			"linkUpdates" : [
   				{
   					"linkId" : "11",
   					"linkedElementUpdate" : {
   						"linkedElementId" : "1",
   						"linkedElementVersion" : "23"
   					}
   				}
   			]
   		},
   	]
   }

---

## 10. Edge Type renamed

    Stream:
    {
    "from" : "[20,subgraph0:23,subgraph1:23]",
    "graphName" : "graph0",

    	"edgeTypes" : [
    		{
    			"elementId" : "7",
    			"version" : "24",
    			"key" : "edgeTypeKey1-1",
    			"content" : "<sample edge type-1>",
    			
    			"edgeTypeName" : "edgeType0-1"
    		}
    	],
    	
    	"subgraphs" : [
    		{
    			"name" : "subgraph0",
    			"subgraphVersionTo" : "24",
    		
    			"linkUpdates" : [
    				{
    					"linkId" : "9",
    					"linkedElementUpdate" : {
    						"linkedElementId" : "7",
    						"linkedElementVersion" : "24"
    					}
    				}
    			]
    		},
    		{
    			"name" : "subgraph1",
    			"subgraphVersionTo" : "24",
    		
    			"linkUpdates" : [
    				{
    					"linkId" : "14",
    					"linkedElementUpdate" : {
    						"linkedElementId" : "7",
    						"linkedElementVersion" : "24"
    					}
    				}
    			]
    		}
    	]
    }

---

## 11. Delete link
    Notes:
    a. Vertex type/edge type links can only be deleted if no corresponding vertexes/edges of that type are linked to subgraph (including tombstoned vertexes/edges)
    Logically that refers to the fact that a vertex/edge (even tombstoned ones) can't be of nonexistent type, since tombstoned vertex/edge might be recovered.

b. Vertex link can only be deleted if no connected edges are linked to subgraph (including tombstoned edges)
Logically that refers to the fact that an edge (even a tombstoned one) can't point to a nonexistent vertex, since tombstoned edge might be recovered.

c. I.e. delete edges first, vertexes after, and then types (it's also ok to delete edges, vertexes and types together in the same transaction)

d. Involves sending element sync list to a given subgraph

e. deleting an element from all subgraphs effectively removes an attached element from active elements set, and therefore from RAM or DB cache.
Such element may still exist in SourceDB and later be relinked to some subgraph, but for all stream recepients (RAM cache; proxy DBs) is unreachable.

	Stream:
	//remove link id 15 / edge id 8
	{
		"from" : "[20,subgraph0:24,subgraph1:24]",
		"graphName" : "graph0",

		"subgraphs" : [
			{
				"name" : "subgraph1",
				"subgraphVersionTo" : "25",
			
				"elementSync" : {
					"elementSyncVersion" : "25",
					"elementIds" : [ "11", "12", "13", "14" ]
				}
			}
		]
	}

---

## 12. Delete vertex, edge, vertex type, edge type
    Notes:
    a. essentially deleting all element's links to subgraphs
    b. by doing so, creates orphaned elements that should be removed from cache, as described in 11.e

    See 11

---

## 13. Delete subgraph
    Notes:
    a. involves sending Subgraph sync list for a graph
    b. Orphaned elements should be removed from cache, similarly to 11.e

    Stream:
    //remove subgraph1
    {
    "from" : "[20,subgraph0:24,subgraph1:25]",
    "graphName" : "graph0",

    	"subgraphSync" : {
    		"subgraphSyncVersion" : "26",
    		"subgraphNames" : [ "subgraph0" ]
    	}
    }

---

## 14. GraphElement deleted

    Stream:
    {
    "from" : "[26,subgraph0:24]",
    "graphName" : "graph0",

    	"graphElementRecord" : {
    		"graphElementUpdateVersion" : "27"
    	}
    }

---

## 15. SubgraphElement deleted

    Stream:
    {
    "from" : "[27,subgraph0:24]",
    "graphName" : "graph0",

    	"subgraphs" : [
    		{
    			"name" : "subgraph0",
    			"subgraphVersionTo" : "28",
    			
    			"subgraphElementRecord" : {
    				"subgraphElementUpdateVersion" : "28"
    			}
    		}
    	]
    }

---

## 16. Destroy / Recover graph
    Notes: Special rules apply to how graph diffs are constructed in presence of Destroy / Recover graph events.

a. If graph is destroyed and requester's family version is behind destroy version, the diff will contain only destroyed record (with field "isDestroyed" : true).
Client cache should completely wipe out all contents, including subgraphs, edge/vertex types and graph element, essentially everything other than graph record intself upon reception of such delta.
Effectively, the version is reduced to graph version, since all subgraphs will be deleted.

b. If graph is Recovered, and requester's family version is behind recover version, the diff will contain destroyed record (with field "isDestroyed" : false), and all the graph data to rebuild its cache, as if the request was made from minimal version ([]).
In this case all data destroyed when a graph destruction diff was received will be restored, since the entire graph will be included in the recovery diff.

c. It's possible that requester would receive the recovery diff (described in (b)), but never have received the destruction delta (described in (a)), due to some (connectivity) problems.
Considering that a graph can't be destroyed or recovered twice in a row, and destruction and recovery are strictly alternating events, and any graph recovery follows previous graph destruction - it's safe to assume that graph recovery that's described in recovery diff was preceded by destruction.
Therefore, to bring cache contents up to date, the following needs to be done:
- graph destruction, as described in (a), followed by
- graph recovery, as described in (b).

### 16.1 Destroy graph

	Stream:
	{
		"from" : "[27,subgraph0:28]",
		"graphName" : "graph0",
		
		"destroyedRecord" : {
			"destroyRecoverVersion" : "29",
			"isDestroyed" : true
		}
	}

### 16.2 Recover graph

	Stream:
	{
		"from" : "[29]",
		"graphName" : "graph0",
		
		"destroyedRecord" : {
			"destroyRecoverVersion" : "30",
			"isDestroyed" : false
		},
		
		//+the entire graph contents, as if requested (from == [])
	}
