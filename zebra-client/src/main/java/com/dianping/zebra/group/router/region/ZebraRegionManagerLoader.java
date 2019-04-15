/*
 * Copyright (c) 2011-2018, Meituan Dianping. All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dianping.zebra.group.router.region;

import com.dianping.zebra.Constants;
import com.dianping.zebra.config.ConfigService;

public class ZebraRegionManagerLoader {

	private volatile static ZebraRegionManager regionManager;

	public static ZebraRegionManager getRegionManager(String configManagerType, ConfigService configService) {
		if (regionManager == null) {
			synchronized (ZebraRegionManagerLoader.class) {
				if (regionManager == null) {
					ZebraRegionManager manager = null;
					if (Constants.CONFIG_MANAGER_TYPE_LOCAL.equalsIgnoreCase(configManagerType)) {
						manager = new LocalRegionManager();
						manager.init();
					} else {
						manager = new RemoteRegionManager(configManagerType, configService);
						manager.init();
					}

					regionManager = manager;
				}
			}
		}

		return regionManager;
	}
}
