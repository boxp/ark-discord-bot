"""Discord bot for ARK server management."""

import logging
from typing import Dict, Any

import discord
from discord.ext import commands

from .kubernetes_manager import KubernetesManager
from .rcon_manager import RconManager

logger = logging.getLogger(__name__)


class ArkDiscordBot(commands.Bot):
    """Discord bot for ARK server management."""

    def __init__(self, config: Dict[str, Any]):
        """Initialize ArkDiscordBot.

        Args:
            config: Configuration dictionary
        """
        intents = discord.Intents.default()
        intents.message_content = True

        super().__init__(command_prefix='!ark ', intents=intents, help_command=None)

        self.config = config
        self.channel_id = config['channel_id']

        # Initialize managers
        self.kubernetes_manager = KubernetesManager(
            namespace=config['kubernetes_namespace'],
            deployment_name=config['kubernetes_deployment_name'],
            service_name=config['kubernetes_service_name']
        )

        self.rcon_manager = RconManager(
            host=config['rcon_host'],
            port=config['rcon_port'],
            password=config['rcon_password']
        )

        # Add commands
        self.add_commands()

    def add_commands(self):
        """Add bot commands."""
        @self.command(name='help')
        async def help_cmd(ctx):
            await self.help_command(ctx)

        @self.command(name='restart')
        async def restart_cmd(ctx):
            await self.restart_command(ctx)

        @self.command(name='players')
        async def players_cmd(ctx):
            await self.players_command(ctx)

        @self.command(name='status')
        async def status_cmd(ctx):
            await self.status_command(ctx)

    async def help_command(self, ctx):
        """Display help information."""
        help_text = """
**🦕 ARK Server Management Commands**

`!ark help` - Show this help message
`!ark status` - Check current server status
`!ark restart` - Restart the ARK server
`!ark players` - List current online players

**📋 Usage Examples:**
• `!ark status` - Check if server is running
• `!ark players` - See who's online
• `!ark restart` - Restart server (use with caution!)

**ℹ️ Note:** Server restart may take several minutes to complete.
        """
        await ctx.send(help_text)

    async def restart_command(self, ctx):
        """Handle server restart command."""
        try:
            logger.info(f"Server restart requested by {ctx.author}")

            success = await self.kubernetes_manager.restart_server()

            if success:
                await ctx.send("🔄 ARK Server restart initiated! Please wait for the server to come back online.")
            else:
                await ctx.send("❌ Failed to restart ARK Server. Please check the logs or contact an administrator.")

        except Exception as e:
            logger.error(f"Error in restart command: {e}")
            await ctx.send("❌ An error occurred while restarting the server.")

    async def players_command(self, ctx):
        """Handle players list command."""
        try:
            players = await self.rcon_manager.get_online_players()

            if players:
                player_list = "\n".join([f"• {player}" for player in players])
                message = f"👥 **{len(players)} players online:**\n{player_list}"
            else:
                message = "🏝️ No players are currently online."

            await ctx.send(message)

        except Exception as e:
            logger.error(f"Error in players command: {e}")
            await ctx.send("❌ Failed to get player information. Server might be offline or RCON unavailable.")

    async def status_command(self, ctx):
        """Handle server status command."""
        try:
            status = await self.kubernetes_manager.get_server_status()

            if status == "running":
                message = "🟢 ARK Server is running and ready for connections!"
            elif status == "not_ready":
                message = "🟡 ARK Server is starting up or not ready..."
            elif status == "error":
                message = "🔴 ARK Server encountered an error! Please check the logs."
            else:
                message = f"❓ Unknown server status: {status}"

            await ctx.send(message)

        except Exception as e:
            logger.error(f"Error in status command: {e}")
            await ctx.send("❌ Failed to get server status.")

    async def send_message(self, channel_id: int, message: str):
        """Send message to specific channel.

        Args:
            channel_id: Discord channel ID
            message: Message to send
        """
        try:
            channel = self.get_channel(channel_id)
            if channel:
                await channel.send(message)
            else:
                logger.error(f"Channel {channel_id} not found")

        except Exception as e:
            logger.error(f"Error sending message to channel {channel_id}: {e}")

    async def on_ready(self):
        """Handle bot ready event."""
        logger.info(f"Bot {self.user.name} has connected to Discord!")

    async def on_command_error(self, ctx, error):
        """Handle command errors."""
        if isinstance(error, commands.CommandNotFound):
            # Ignore unknown commands
            return

        logger.error(f"Command error: {error}")
        await ctx.send("❌ An error occurred while processing the command.")
