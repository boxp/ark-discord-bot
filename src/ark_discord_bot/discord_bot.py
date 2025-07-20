"""Discord bot for ARK server management."""

import logging
from typing import Any, Dict

import discord
from discord.ext import commands

from .kubernetes_manager import KubernetesManager
from .rcon_manager import RconManager
from .server_status_checker import ServerStatusChecker

logger = logging.getLogger(__name__)


class RestartConfirmationView(discord.ui.View):
    """View for restart confirmation dialog."""

    def __init__(self, bot: "ArkDiscordBot", timeout=60):
        super().__init__(timeout=timeout)
        self.bot = bot
        self.confirmed = False

    @discord.ui.button(label="再起動する", style=discord.ButtonStyle.danger, emoji="🔄")
    async def confirm_restart(
        self,
        interaction: discord.Interaction,
        button: discord.ui.Button,  # pylint: disable=unused-argument
    ):
        """Handle restart confirmation."""
        self.confirmed = True

        # Disable all buttons
        for item in self.children:
            item.disabled = True

        await interaction.response.edit_message(
            content="🔄 ARKサーバーの再起動を開始しています...", view=self
        )

        try:
            success = await self.bot.kubernetes_manager.restart_server()

            if success:
                await interaction.followup.send(
                    "✅ ARKサーバーの再起動を開始しました！サーバーがオンラインに戻るまでしばらくお待ちください。"
                )
            else:
                await interaction.followup.send(
                    "❌ ARKサーバーの再起動に失敗しました。ログを確認するか管理者にお問い合わせください。"
                )
        except Exception as e:
            logger.error(f"Error during server restart: {e}")
            await interaction.followup.send(
                "❌ サーバー再起動中にエラーが発生しました。"
            )

    @discord.ui.button(
        label="キャンセル", style=discord.ButtonStyle.secondary, emoji="❌"
    )
    async def cancel_restart(
        self,
        interaction: discord.Interaction,
        button: discord.ui.Button,  # pylint: disable=unused-argument
    ):
        """Handle restart cancellation."""
        # Disable all buttons
        for item in self.children:
            item.disabled = True

        await interaction.response.edit_message(
            content="❌ ARKサーバーの再起動がキャンセルされました。", view=self
        )

    async def on_timeout(self):
        """Handle timeout."""
        # Disable all buttons
        for item in self.children:
            item.disabled = True


class ArkDiscordBot(commands.Bot):
    """Discord bot for ARK server management."""

    def __init__(self, config: Dict[str, Any]):
        """Initialize ArkDiscordBot.

        Args:
            config: Configuration dictionary
        """
        intents = discord.Intents.default()
        intents.message_content = True

        super().__init__(command_prefix="!ark ", intents=intents, help_command=None)

        self.config = config
        self.channel_id = config["channel_id"]

        # Initialize managers
        self.kubernetes_manager = KubernetesManager(
            namespace=config["kubernetes_namespace"],
            deployment_name=config["kubernetes_deployment_name"],
            service_name=config["kubernetes_service_name"],
        )

        self.rcon_manager = RconManager(
            host=config["rcon_host"],
            port=config["rcon_port"],
            password=config["rcon_password"],
        )

        self.server_status_checker = ServerStatusChecker(
            kubernetes_manager=self.kubernetes_manager,
            rcon_manager=self.rcon_manager,
        )

        # Add commands
        self.add_commands()

    def add_commands(self):
        """Add bot commands."""

        @self.command(name="help")
        async def help_cmd(ctx):
            await self.help_command_method(ctx)

        @self.command(name="restart")
        async def restart_cmd(ctx):
            await self.restart_command(ctx)

        @self.command(name="players")
        async def players_cmd(ctx):
            await self.players_command(ctx)

        @self.command(name="status")
        async def status_cmd(ctx):
            await self.status_command(ctx)

    async def help_command_method(self, ctx):
        """Display help information."""
        help_text = """
**🦕 ARKサーバー管理コマンド**

`!ark help` - このヘルプメッセージを表示
`!ark status` - 現在のサーバーステータスを確認
`!ark restart` - ARKサーバーを再起動
`!ark players` - 現在オンラインのプレイヤー一覧を表示

**📋 使用例:**
• `!ark status` - サーバーが稼働中か確認
• `!ark players` - オンラインプレイヤーを確認
• `!ark restart` - サーバーを再起動（注意して使用）

**ℹ️ 注意:** サーバー再起動は完了まで数分かかる場合があります。
        """
        await ctx.send(help_text)

    async def restart_command(self, ctx):
        """Handle server restart command with confirmation dialog."""
        try:
            logger.info(f"Server restart requested by {ctx.author}")

            # Create confirmation view
            view = RestartConfirmationView(self)

            embed = discord.Embed(
                title="⚠️ ARKサーバー再起動の確認",
                description="本当にARKサーバーを再起動しますか？\n\n"
                "⚠️ **注意**: 再起動中はプレイヤーが切断され、"
                "サーバーが再度利用可能になるまで数分かかります。",
                color=0xFF9900,
            )

            await ctx.send(embed=embed, view=view)

        except Exception as e:
            logger.error(f"Error in restart command: {e}")
            await ctx.send("❌ サーバー再起動コマンドでエラーが発生しました。")

    async def players_command(self, ctx):
        """Handle players list command."""
        try:
            players = await self.rcon_manager.get_online_players()

            if players:
                player_list = "\n".join([f"• {player}" for player in players])
                message = f"👥 **現在{len(players)}人のプレイヤーがオンライン:**\n{player_list}"
            else:
                message = "🏝️ 現在オンラインのプレイヤーはいません。"

            await ctx.send(message)

        except Exception as e:
            logger.error(f"Error in players command: {e}")
            await ctx.send(
                "❌ プレイヤー情報の取得に失敗しました。サーバーがオフラインかRCONが利用できない可能性があります。"
            )

    async def status_command(self, ctx):
        """Handle server status command using comprehensive RCON-based connectivity check."""
        try:
            status = await self.server_status_checker.get_server_status()

            if status == "running":
                message = "🟢 ARKサーバーは稼働中で接続準備完了です！"
            elif status == "starting":
                message = "🟡 ARKサーバーポッドは稼働中ですが、ゲームサーバーはまだ起動中です。もう少しお待ちください..."
            elif status == "not_ready":
                message = "🟡 ARKサーバーは起動中または準備未完了です..."
            elif status == "error":
                message = (
                    "🔴 ARKサーバーでエラーが発生しました！ログを確認してください。"
                )
            else:
                message = f"❓ 不明なサーバーステータス: {status}"

            await ctx.send(message)

        except Exception as e:
            logger.error(f"Error in status command: {e}")
            await ctx.send("❌ サーバーステータスの取得に失敗しました。")

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
        await ctx.send("❌ コマンド処理中にエラーが発生しました。")
