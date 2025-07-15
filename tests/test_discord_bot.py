"""Tests for Discord bot functionality."""

import pytest
from unittest.mock import Mock, patch, AsyncMock
import discord

from src.ark_discord_bot.discord_bot import ArkDiscordBot


class TestArkDiscordBot:
    """Tests for ArkDiscordBot class."""

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

    @pytest.fixture
    def discord_bot(self, mock_config):
        """Create an ArkDiscordBot instance for testing."""
        return ArkDiscordBot(mock_config)

    @pytest.mark.asyncio
    async def test_help_command(self, discord_bot):
        """Test help command functionality."""
        # Mock Discord context
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        await discord_bot.help_command(mock_ctx)
        
        # Verify help message was sent
        mock_ctx.send.assert_called_once()
        call_args = mock_ctx.send.call_args[0][0]
        assert "ARK Server Management Commands" in call_args
        assert "!ark restart" in call_args
        assert "!ark players" in call_args
        assert "!ark status" in call_args
        assert "!ark help" in call_args

    @pytest.mark.asyncio
    async def test_restart_command_success(self, discord_bot):
        """Test successful restart command."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        discord_bot.kubernetes_manager.restart_server = AsyncMock(return_value=True)
        
        await discord_bot.restart_command(mock_ctx)
        
        # Verify restart was called and success message sent
        discord_bot.kubernetes_manager.restart_server.assert_called_once()
        mock_ctx.send.assert_called_with("🔄 ARK Server restart initiated! Please wait for the server to come back online.")

    @pytest.mark.asyncio
    async def test_restart_command_failure(self, discord_bot):
        """Test failed restart command."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        discord_bot.kubernetes_manager.restart_server = AsyncMock(return_value=False)
        
        await discord_bot.restart_command(mock_ctx)
        
        # Verify restart was called and failure message sent
        discord_bot.kubernetes_manager.restart_server.assert_called_once()
        mock_ctx.send.assert_called_with("❌ Failed to restart ARK Server. Please check the logs or contact an administrator.")

    @pytest.mark.asyncio
    async def test_players_command_with_players(self, discord_bot):
        """Test players command when players are online."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        discord_bot.rcon_manager.get_online_players = AsyncMock(
            return_value=["Player1", "Player2", "Player3"]
        )
        
        await discord_bot.players_command(mock_ctx)
        
        # Verify players were fetched and message sent
        discord_bot.rcon_manager.get_online_players.assert_called_once()
        mock_ctx.send.assert_called_once()
        call_args = mock_ctx.send.call_args[0][0]
        assert "3 players online" in call_args
        assert "Player1" in call_args
        assert "Player2" in call_args
        assert "Player3" in call_args

    @pytest.mark.asyncio
    async def test_players_command_no_players(self, discord_bot):
        """Test players command when no players are online."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        discord_bot.rcon_manager.get_online_players = AsyncMock(return_value=[])
        
        await discord_bot.players_command(mock_ctx)
        
        # Verify message for no players
        mock_ctx.send.assert_called_with("🏝️ No players are currently online.")

    @pytest.mark.asyncio
    async def test_players_command_error(self, discord_bot):
        """Test players command when RCON fails."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        discord_bot.rcon_manager.get_online_players = AsyncMock(
            side_effect=Exception("RCON error")
        )
        
        await discord_bot.players_command(mock_ctx)
        
        # Verify error message sent
        mock_ctx.send.assert_called_with("❌ Failed to get player information. Server might be offline or RCON unavailable.")

    @pytest.mark.asyncio
    async def test_status_command_running(self, discord_bot):
        """Test status command when server is running."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        discord_bot.kubernetes_manager.get_server_status = AsyncMock(return_value="running")
        
        await discord_bot.status_command(mock_ctx)
        
        # Verify status message
        mock_ctx.send.assert_called_with("🟢 ARK Server is running and ready for connections!")

    @pytest.mark.asyncio
    async def test_status_command_not_ready(self, discord_bot):
        """Test status command when server is not ready."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        discord_bot.kubernetes_manager.get_server_status = AsyncMock(return_value="not_ready")
        
        await discord_bot.status_command(mock_ctx)
        
        # Verify status message
        mock_ctx.send.assert_called_with("🟡 ARK Server is starting up or not ready...")

    @pytest.mark.asyncio
    async def test_status_command_error(self, discord_bot):
        """Test status command when server has error."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        discord_bot.kubernetes_manager.get_server_status = AsyncMock(return_value="error")
        
        await discord_bot.status_command(mock_ctx)
        
        # Verify error status message
        mock_ctx.send.assert_called_with("🔴 ARK Server encountered an error! Please check the logs.")

    @pytest.mark.asyncio
    async def test_send_message(self, discord_bot):
        """Test sending message to channel."""
        mock_channel = Mock()
        mock_channel.send = AsyncMock()
        
        discord_bot.get_channel = Mock(return_value=mock_channel)
        
        await discord_bot.send_message(123456789, "Test message")
        
        # Verify message was sent to correct channel
        discord_bot.get_channel.assert_called_with(123456789)
        mock_channel.send.assert_called_with("Test message")

    @pytest.mark.asyncio
    async def test_send_message_channel_not_found(self, discord_bot):
        """Test sending message when channel not found."""
        discord_bot.get_channel = Mock(return_value=None)
        
        # Should not raise exception
        await discord_bot.send_message(123456789, "Test message")

    @pytest.mark.asyncio
    async def test_on_ready(self, discord_bot):
        """Test on_ready event."""
        discord_bot.user = Mock()
        discord_bot.user.name = "TestBot"
        
        with patch('src.ark_discord_bot.discord_bot.logger') as mock_logger:
            await discord_bot.on_ready()
            mock_logger.info.assert_called_with("Bot TestBot has connected to Discord!")

    @pytest.mark.asyncio
    async def test_on_command_error_command_not_found(self, discord_bot):
        """Test handling of command not found error."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        error = discord.ext.commands.CommandNotFound()
        
        await discord_bot.on_command_error(mock_ctx, error)
        
        # Should not send any message for CommandNotFound
        mock_ctx.send.assert_not_called()

    @pytest.mark.asyncio
    async def test_on_command_error_other_error(self, discord_bot):
        """Test handling of other command errors."""
        mock_ctx = Mock()
        mock_ctx.send = AsyncMock()
        
        error = Exception("Test error")
        
        await discord_bot.on_command_error(mock_ctx, error)
        
        # Should send error message
        mock_ctx.send.assert_called_with("❌ An error occurred while processing the command.")