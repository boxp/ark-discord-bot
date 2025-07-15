"""Kubernetes manager for ARK server operations."""

import asyncio
import logging
from datetime import datetime
from typing import Optional

from kubernetes import client, config
from kubernetes.client.rest import ApiException

logger = logging.getLogger(__name__)


class KubernetesManager:
    """Manages Kubernetes operations for ARK server."""

    def __init__(self, namespace: str, deployment_name: str, service_name: str):
        """Initialize KubernetesManager.
        
        Args:
            namespace: Kubernetes namespace
            deployment_name: Name of the deployment
            service_name: Name of the service
        """
        self.namespace = namespace
        self.deployment_name = deployment_name
        self.service_name = service_name
        
        # Load Kubernetes config
        try:
            config.load_incluster_config()
        except config.ConfigException:
            config.load_kube_config()

    async def restart_server(self) -> bool:
        """Restart the ARK server using rollout restart.
        
        Returns:
            bool: True if restart was successful, False otherwise
        """
        try:
            apps_v1 = client.AppsV1Api()
            
            # Create patch to trigger rollout restart
            patch_body = {
                "spec": {
                    "template": {
                        "metadata": {
                            "annotations": {
                                "kubectl.kubernetes.io/restartedAt": datetime.utcnow().isoformat()
                            }
                        }
                    }
                }
            }
            
            # Apply patch to trigger restart
            await asyncio.get_event_loop().run_in_executor(
                None,
                apps_v1.patch_namespaced_deployment,
                self.deployment_name,
                self.namespace,
                patch_body
            )
            
            logger.info(f"Successfully triggered restart for {self.deployment_name}")
            return True
            
        except ApiException as e:
            logger.error(f"Failed to restart server: {e}")
            return False
        except Exception as e:
            logger.error(f"Unexpected error during restart: {e}")
            return False

    async def get_server_status(self) -> str:
        """Get the current status of the ARK server.
        
        Returns:
            str: Status of the server ('running', 'not_ready', 'error')
        """
        try:
            apps_v1 = client.AppsV1Api()
            
            deployment = await asyncio.get_event_loop().run_in_executor(
                None,
                apps_v1.read_namespaced_deployment,
                self.deployment_name,
                self.namespace
            )
            
            if deployment.status.ready_replicas and deployment.status.ready_replicas > 0:
                return "running"
            else:
                return "not_ready"
                
        except ApiException as e:
            logger.error(f"Failed to get server status: {e}")
            return "error"
        except Exception as e:
            logger.error(f"Unexpected error getting server status: {e}")
            return "error"