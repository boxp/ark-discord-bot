"""Main application entry point for ARK Discord Bot."""

import asyncio
import logging
import signal
import sys
from typing import Optional

from .config import get_config
from .discord_bot import ArkDiscordBot
from .server_monitor import ServerMonitor


class ArkBotApplication:
    """Main application class for ARK Discord Bot."""

    def __init__(self):
        """Initialize the application."""
        self.config = get_config()
        self.setup_logging()
        
        self.bot: Optional[ArkDiscordBot] = None
        self.monitor: Optional[ServerMonitor] = None
        self.monitor_task: Optional[asyncio.Task] = None
        
        # Setup signal handlers
        signal.signal(signal.SIGINT, self._signal_handler)
        signal.signal(signal.SIGTERM, self._signal_handler)

    def setup_logging(self):
        """Setup logging configuration."""
        log_level = getattr(logging, self.config['log_level'].upper(), logging.INFO)
        
        logging.basicConfig(
            level=log_level,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            handlers=[
                logging.StreamHandler(sys.stdout),
                logging.FileHandler('ark_discord_bot.log')
            ]
        )
        
        # Reduce discord.py logging level
        logging.getLogger('discord').setLevel(logging.WARNING)
        logging.getLogger('discord.http').setLevel(logging.WARNING)

    async def start(self):
        """Start the bot application."""
        try:
            logger = logging.getLogger(__name__)
            logger.info("Starting ARK Discord Bot...")
            
            # Initialize bot
            self.bot = ArkDiscordBot(self.config)
            
            # Initialize server monitor
            self.monitor = ServerMonitor(
                kubernetes_manager=self.bot.kubernetes_manager,
                discord_bot=self.bot,
                channel_id=self.config['channel_id'],
                check_interval=self.config['monitoring_interval']
            )
            
            # Start monitoring task
            self.monitor_task = asyncio.create_task(self.monitor.start_monitoring())
            
            # Start bot
            logger.info("Connecting to Discord...")
            await self.bot.start(self.config['discord_token'])
            
        except Exception as e:
            logger = logging.getLogger(__name__)
            logger.error(f"Error starting bot: {e}")
            await self.stop()
            raise

    async def stop(self):
        """Stop the bot application."""
        logger = logging.getLogger(__name__)
        logger.info("Shutting down ARK Discord Bot...")
        
        # Stop monitoring
        if self.monitor:
            self.monitor.stop_monitoring()
        
        if self.monitor_task:
            self.monitor_task.cancel()
            try:
                await self.monitor_task
            except asyncio.CancelledError:
                pass
        
        # Stop bot
        if self.bot:
            await self.bot.close()
        
        logger.info("ARK Discord Bot stopped.")

    def _signal_handler(self, signum, frame):
        """Handle shutdown signals."""
        logger = logging.getLogger(__name__)
        logger.info(f"Received signal {signum}, shutting down...")
        
        # Create shutdown task
        loop = asyncio.get_event_loop()
        loop.create_task(self.stop())


async def main():
    """Main application entry point."""
    app = ArkBotApplication()
    
    try:
        await app.start()
    except KeyboardInterrupt:
        pass
    except Exception as e:
        logger = logging.getLogger(__name__)
        logger.error(f"Application error: {e}")
        sys.exit(1)
    finally:
        await app.stop()


if __name__ == "__main__":
    asyncio.run(main())