Great question — this is a classic hybrid/multi-site architecture pattern. Let me walk through the scenarios where a CVS site would failover from its on-prem EDGE environment to the centralized GCP disaster recovery environment.
Here are the key scenarios, grouped by category:
Infrastructure Failures

Complete site power outage — Prolonged power loss at the physical location exceeding UPS/generator capacity, forcing workloads to shift to GCP.
Network connectivity loss — The site loses WAN/internet connectivity, making it unreachable by users or upstream systems. GCP takes over to maintain service continuity.
Hardware failure (compute/storage) — Critical server or storage array failure at the edge with no local redundancy left to absorb the load.
Cooling/environmental failure — HVAC failure in the server room causing thermal shutdowns of on-prem equipment.

Natural Disasters & Physical Events

Natural disaster — Flood, hurricane, earthquake, tornado, or wildfire rendering the physical site inaccessible or unsafe.
Fire or water damage — Localized incidents (sprinkler activation, electrical fire) that damage on-prem infrastructure.
Pandemic or access restrictions — Staff cannot physically access the site for maintenance, and remote management is insufficient to keep systems healthy.

Planned Operations

Scheduled maintenance or upgrades — Firmware updates, OS patching, or hardware refresh at the edge site that requires full downtime. Traffic is pre-migrated to GCP during the maintenance window.
DR drill / failover testing — Regular disaster recovery exercises to validate that GCP can handle the site's workload and that failback works cleanly.
Capacity overflow (cloud bursting) — Seasonal or event-driven traffic spikes (e.g., flu season, open enrollment) exceed on-prem capacity, and GCP absorbs the overflow.

Security & Compliance

Security breach or ransomware — The edge environment is compromised and must be isolated for forensic investigation. GCP serves as a clean, known-good environment while remediation happens.
Compliance-driven isolation — A regulatory audit or vulnerability finding requires the on-prem environment to be taken offline for remediation.

Software & Application Failures

Corrupted data or application state — A bad deployment, database corruption, or configuration drift at the edge makes the local environment unreliable. GCP provides a last-known-good state.
Failed deployment / rollback scenario — A software release at the edge site goes wrong and local rollback isn't possible, so traffic is redirected to GCP running the stable version.

Operational / Business Decisions

Site decommissioning or consolidation — A business decision to permanently close or consolidate a physical site, with GCP absorbing its workload during the transition.
Cost optimization — Running certain workloads on GCP becomes more cost-effective than maintaining aging on-prem hardware, triggering a deliberate migration.
Performance degradation — Gradual on-prem performance issues (disk I/O bottlenecks, memory pressure) that can't be resolved quickly, making GCP the better option for end-user experience.

Key Considerations When Switching
There are a few things worth keeping in mind across all these scenarios. Data synchronization and RPO/RTO targets matter — you need to understand how much data you can afford to lose and how quickly GCP must be serving traffic. DNS TTL and traffic routing (e.g., through a global load balancer or GSLB) directly affect how fast the switchover is visible to clients. Failback planning is just as important as failover, since returning to EDGE after GCP takes over often introduces its own risks around data reconciliation. Finally, monitoring and automated health checks should ideally trigger the switch (or at least alert) before a human notices the problem.
Would you like me to dive deeper into any of these scenarios, or create a document covering the failover decision matrix in more detail?
