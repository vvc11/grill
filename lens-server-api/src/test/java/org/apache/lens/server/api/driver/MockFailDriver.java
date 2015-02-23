/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.lens.server.api.driver;

import org.apache.lens.api.LensException;
<<<<<<< HEAD:lens-server-api/src/test/java/org/apache/lens/server/api/driver/MockFailDriver.java
=======
import org.apache.lens.api.query.QueryCost;
>>>>>>> e3ff7daa540cc4b0225ee5aa5384bc7cd49c06d7:lens-server-api/src/test/java/org/apache/lens/server/api/driver/MockFailDriver.java
import org.apache.lens.server.api.query.AbstractQueryContext;

public class MockFailDriver extends MockDriver {

  @Override
  public QueryCost estimate(AbstractQueryContext qctx) throws LensException {
    throw new LensException("failing!");
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.driver.MockDriver#explain(java.lang.String, org.apache.hadoop.conf.Configuration)
   */
  public DriverQueryPlan explain(AbstractQueryContext explainCtx) throws LensException {
    throw new LensException("failing!");
  }
}
