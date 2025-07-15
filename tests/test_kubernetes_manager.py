"""Tests for Kubernetes manager functionality."""

import pytest
from unittest.mock import Mock, patch, AsyncMock
from kubernetes.client.rest import ApiException

from src.ark_discord_bot.kubernetes_manager import KubernetesManager


class TestKubernetesManager:
    """Tests for KubernetesManager class."""

    @pytest.fixture
    def k8s_manager(self):
        """Create a KubernetesManager instance for testing."""
        return KubernetesManager(
            namespace="ark-survival-ascended",
            deployment_name="ark-server",
            service_name="ark-server-service"
        )

    @pytest.mark.asyncio
    async def test_restart_server_success(self, k8s_manager):
        """Test successful server restart."""
        with patch('src.ark_discord_bot.kubernetes_manager.client.AppsV1Api') as mock_apps_v1:
            mock_api = Mock()
            mock_apps_v1.return_value = mock_api
            
            # Mock successful patch response
            mock_api.patch_namespaced_deployment.return_value = Mock()
            
            result = await k8s_manager.restart_server()
            
            assert result is True
            mock_api.patch_namespaced_deployment.assert_called_once_with(
                name="ark-server",
                namespace="ark-survival-ascended",
                body={
                    "spec": {
                        "template": {
                            "metadata": {
                                "annotations": {
                                    "kubectl.kubernetes.io/restartedAt": pytest.approx(
                                        mock_api.patch_namespaced_deployment.call_args[1]["body"]["spec"]["template"]["metadata"]["annotations"]["kubectl.kubernetes.io/restartedAt"], 
                                        abs=1
                                    )
                                }
                            }
                        }
                    }
                }
            )

    @pytest.mark.asyncio
    async def test_restart_server_api_exception(self, k8s_manager):
        """Test server restart when API exception occurs."""
        with patch('src.ark_discord_bot.kubernetes_manager.client.AppsV1Api') as mock_apps_v1:
            mock_api = Mock()
            mock_apps_v1.return_value = mock_api
            
            # Mock API exception
            mock_api.patch_namespaced_deployment.side_effect = ApiException(
                status=404, reason="Not Found"
            )
            
            result = await k8s_manager.restart_server()
            
            assert result is False

    @pytest.mark.asyncio
    async def test_get_server_status_running(self, k8s_manager):
        """Test get_server_status when server is running."""
        with patch('src.ark_discord_bot.kubernetes_manager.client.AppsV1Api') as mock_apps_v1:
            mock_api = Mock()
            mock_apps_v1.return_value = mock_api
            
            # Mock deployment response
            mock_deployment = Mock()
            mock_deployment.status.ready_replicas = 1
            mock_deployment.status.replicas = 1
            mock_api.read_namespaced_deployment.return_value = mock_deployment
            
            status = await k8s_manager.get_server_status()
            
            assert status == "running"

    @pytest.mark.asyncio
    async def test_get_server_status_not_ready(self, k8s_manager):
        """Test get_server_status when server is not ready."""
        with patch('src.ark_discord_bot.kubernetes_manager.client.AppsV1Api') as mock_apps_v1:
            mock_api = Mock()
            mock_apps_v1.return_value = mock_api
            
            # Mock deployment response
            mock_deployment = Mock()
            mock_deployment.status.ready_replicas = 0
            mock_deployment.status.replicas = 1
            mock_api.read_namespaced_deployment.return_value = mock_deployment
            
            status = await k8s_manager.get_server_status()
            
            assert status == "not_ready"

    @pytest.mark.asyncio
    async def test_get_server_status_error(self, k8s_manager):
        """Test get_server_status when API error occurs."""
        with patch('src.ark_discord_bot.kubernetes_manager.client.AppsV1Api') as mock_apps_v1:
            mock_api = Mock()
            mock_apps_v1.return_value = mock_api
            
            # Mock API exception
            mock_api.read_namespaced_deployment.side_effect = ApiException(
                status=404, reason="Not Found"
            )
            
            status = await k8s_manager.get_server_status()
            
            assert status == "error"