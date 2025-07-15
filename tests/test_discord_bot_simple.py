"""Simplified tests for Discord bot functionality."""

import pytest
from unittest.mock import Mock, patch, AsyncMock

from src.ark_discord_bot.discord_bot import ArkDiscordBot


class TestArkDiscordBotSimple:
    """Simplified tests for ArkDiscordBot class."""

    @pytest.fixture
    def mock_config(self):
        """Create mock configuration."""
        return {
            'discord_token': 'test_token',
            'channel_id': 123456789,
            'kubernetes_namespace': 'ark-survival-ascended',
            'kubernetes_deployment_name': 'ark-server',
            'kubernetes_service_name': 'ark-server-service',
            'rcon_host': '192.168.10.29',
            'rcon_port': 27020,
            'rcon_password': 'test_password'
        }

    @pytest.mark.asyncio
    async def test_help_command_content(self, mock_config):
        """Test help command returns correct content."""
        with patch('src.ark_discord_bot.discord_bot.KubernetesManager'), \
             patch('src.ark_discord_bot.discord_bot.RconManager'), \
             patch('discord.ext.commands.Bot.__init__'), \
             patch('src.ark_discord_bot.discord_bot.ArkDiscordBot.add_commands'):
            
            bot = ArkDiscordBot(mock_config)
            mock_ctx = Mock()
            mock_ctx.send = AsyncMock()
            
            await bot.help_command(mock_ctx)
            
            # Verify help message was sent
            mock_ctx.send.assert_called_once()
            help_text = mock_ctx.send.call_args[0][0]
            assert "ARK Server Management Commands" in help_text
            assert "!ark restart" in help_text
            assert "!ark players" in help_text
            assert "!ark status" in help_text
            assert "!ark help" in help_text

    @pytest.mark.asyncio
    async def test_restart_command_success(self, mock_config):
        """Test successful restart command."""
        with patch('src.ark_discord_bot.discord_bot.KubernetesManager') as mock_k8s, \
             patch('src.ark_discord_bot.discord_bot.RconManager'), \
             patch('discord.ext.commands.Bot.__init__'), \
             patch('src.ark_discord_bot.discord_bot.ArkDiscordBot.add_commands'):
            
            bot = ArkDiscordBot(mock_config)
            bot.kubernetes_manager = mock_k8s.return_value
            bot.kubernetes_manager.restart_server = AsyncMock(return_value=True)
            
            mock_ctx = Mock()
            mock_ctx.send = AsyncMock()
            mock_ctx.author = "TestUser"
            
            await bot.restart_command(mock_ctx)
            
            # Verify restart was called and success message sent
            bot.kubernetes_manager.restart_server.assert_called_once()
            mock_ctx.send.assert_called_with("🔄 ARK Server restart initiated! Please wait for the server to come back online.")

    @pytest.mark.asyncio
    async def test_players_command_with_players(self, mock_config):
        """Test players command when players are online."""
        with patch('src.ark_discord_bot.discord_bot.KubernetesManager'), \
             patch('src.ark_discord_bot.discord_bot.RconManager') as mock_rcon, \
             patch('discord.ext.commands.Bot.__init__'), \
             patch('src.ark_discord_bot.discord_bot.ArkDiscordBot.add_commands'):
            
            bot = ArkDiscordBot(mock_config)
            bot.rcon_manager = mock_rcon.return_value
            bot.rcon_manager.get_online_players = AsyncMock(
                return_value=["Player1", "Player2", "Player3"]
            )
            
            mock_ctx = Mock()
            mock_ctx.send = AsyncMock()
            
            await bot.players_command(mock_ctx)
            
            # Verify players were fetched and message sent
            bot.rcon_manager.get_online_players.assert_called_once()
            mock_ctx.send.assert_called_once()
            message = mock_ctx.send.call_args[0][0]
            assert "3 players online" in message
            assert "Player1" in message

    @pytest.mark.asyncio
    async def test_status_command_running(self, mock_config):
        """Test status command when server is running."""
        with patch('src.ark_discord_bot.discord_bot.KubernetesManager') as mock_k8s, \
             patch('src.ark_discord_bot.discord_bot.RconManager'), \
             patch('discord.ext.commands.Bot.__init__'), \
             patch('src.ark_discord_bot.discord_bot.ArkDiscordBot.add_commands'):
            
            bot = ArkDiscordBot(mock_config)
            bot.kubernetes_manager = mock_k8s.return_value
            bot.kubernetes_manager.get_server_status = AsyncMock(return_value="running")
            
            mock_ctx = Mock()
            mock_ctx.send = AsyncMock()
            
            await bot.status_command(mock_ctx)
            
            # Verify status message
            mock_ctx.send.assert_called_with("🟢 ARK Server is running and ready for connections!")

    @pytest.mark.asyncio
    async def test_send_message(self, mock_config):
        """Test sending message to channel."""
        with patch('src.ark_discord_bot.discord_bot.KubernetesManager'), \
             patch('src.ark_discord_bot.discord_bot.RconManager'), \
             patch('discord.ext.commands.Bot.__init__'), \
             patch('src.ark_discord_bot.discord_bot.ArkDiscordBot.add_commands'):
            
            bot = ArkDiscordBot(mock_config)
            
            mock_channel = Mock()
            mock_channel.send = AsyncMock()
            bot.get_channel = Mock(return_value=mock_channel)
            
            await bot.send_message(123456789, "Test message")
            
            # Verify message was sent to correct channel
            bot.get_channel.assert_called_with(123456789)
            mock_channel.send.assert_called_with("Test message")