"""RCON manager for ARK server communication."""

import asyncio
import logging
import struct
from typing import List, Optional

try:
    from charset_normalizer import from_bytes
except ImportError:
    from_bytes = None

logger = logging.getLogger(__name__)


class RconManager:
    """Manages RCON communication with ARK server."""

    # RCON packet types
    SERVERDATA_AUTH = 3
    SERVERDATA_EXECCOMMAND = 2
    SERVERDATA_RESPONSE_VALUE = 0
    SERVERDATA_AUTH_RESPONSE = 2

    def __init__(self, host: str, port: int, password: str):
        """Initialize RconManager.

        Args:
            host: RCON server host
            port: RCON server port
            password: RCON password
        """
        self.host = host
        self.port = port
        self.password = password

    def _decode_response(self, data: bytes) -> str:
        """Decode RCON response with proper character encoding detection.

        Args:
            data: Raw bytes from RCON response

        Returns:
            str: Properly decoded string
        """
        if not data:
            return ""

        # Try charset-normalizer for automatic encoding detection if available
        if from_bytes:
            try:
                result = from_bytes(data)
                if result and result.best():
                    return str(result.best())
            except Exception as e:  # pylint: disable=broad-exception-caught
                logger.debug("Charset detection failed: %s", e)

        # Fallback to manual encoding attempts
        encodings = ["utf-8", "windows-1252", "latin1", "cp932", "shift_jis"]

        for encoding in encodings:
            try:
                decoded = data.decode(encoding)
                # Validate the decoded string doesn't contain replacement chars
                if "�" not in decoded or encoding == "latin1":  # latin1 never fails
                    logger.debug("Successfully decoded RCON response using %s", encoding)
                    return decoded
            except (UnicodeDecodeError, UnicodeError):
                continue

        # Last resort: decode with utf-8 and replace errors
        logger.warning(
            "All encoding attempts failed, using UTF-8 with error replacement"
        )
        return data.decode("utf-8", errors="replace")

    async def get_online_players(self) -> List[str]:
        """Get list of online players.

        Returns:
            List[str]: List of player names
        """
        try:
            response = await self.send_command("listplayers")
            if response:
                # Parse the response to extract player names
                players = []
                lines = response.split("\n")
                for line in lines:
                    line = line.strip()
                    if line and not line.startswith("No") and ", " in line:
                        # Extract player name (format: "PlayerName, SteamID")
                        parts = line.split(", ")
                        if len(parts) >= 2:
                            players.append(parts[0])
                return players
            return []
        except Exception as e:  # pylint: disable=broad-exception-caught
            logger.error("Error getting online players: %s", e)
            return []

    async def send_command(self, command: str) -> Optional[str]:
        """Send RCON command to server.

        Args:
            command: Command to send

        Returns:
            Optional[str]: Command response or None if failed
        """
        try:
            reader, writer = await asyncio.open_connection(self.host, self.port)

            # Authenticate
            auth_success = await self._authenticate(reader, writer)
            if not auth_success:
                writer.close()
                await writer.wait_closed()
                return None

            # Send command
            await self._send_packet(writer, self.SERVERDATA_EXECCOMMAND, command)

            # Read response
            response = await self._read_packet(reader)

            writer.close()
            await writer.wait_closed()

            if response and response[0] == self.SERVERDATA_RESPONSE_VALUE:
                return self._decode_response(response[1]).strip()

            return None

        except Exception as e:  # pylint: disable=broad-exception-caught
            logger.error("Error sending RCON command: %s", e)
            return None

    async def _authenticate(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> bool:
        """Authenticate with RCON server.

        Args:
            reader: Stream reader
            writer: Stream writer

        Returns:
            bool: True if authentication successful
        """
        try:
            # Send auth request
            await self._send_packet(writer, self.SERVERDATA_AUTH, self.password)

            # Read auth response
            response = await self._read_packet(reader)

            if response and response[0] == self.SERVERDATA_AUTH_RESPONSE:
                return True

            return False

        except Exception as e:  # pylint: disable=broad-exception-caught
            logger.error("RCON authentication failed: %s", e)
            return False

    async def _send_packet(
        self, writer: asyncio.StreamWriter, packet_type: int, data: str
    ):
        """Send RCON packet.

        Args:
            writer: Stream writer
            packet_type: Type of packet
            data: Data to send
        """
        packet_id = 1
        data_bytes = data.encode("utf-8")

        # Calculate packet size
        packet_size = 4 + 4 + len(data_bytes) + 2  # id + type + data + null terminators

        # Build packet
        packet = struct.pack("<I", packet_size)  # Size
        packet += struct.pack("<I", packet_id)  # ID
        packet += struct.pack("<I", packet_type)  # Type
        packet += data_bytes  # Data
        packet += b"\x00\x00"  # Null terminators

        writer.write(packet)
        await writer.drain()

    async def _read_packet(self, reader: asyncio.StreamReader) -> Optional[tuple]:
        """Read RCON packet.

        Args:
            reader: Stream reader

        Returns:
            Optional[tuple]: (packet_type, data) or None if failed
        """
        try:
            # Read packet size
            size_data = await reader.read(4)
            if len(size_data) < 4:
                return None

            packet_size = struct.unpack("<I", size_data)[0]

            # Read packet data
            packet_data = await reader.read(packet_size)
            if len(packet_data) < packet_size:
                return None

            # Parse packet
            _ = struct.unpack("<I", packet_data[0:4])[0]  # packet_id (unused)
            packet_type = struct.unpack("<I", packet_data[4:8])[0]
            data = packet_data[8:-2]  # Remove null terminators

            return (packet_type, data)

        except Exception as e:  # pylint: disable=broad-exception-caught
            logger.error("Error reading RCON packet: %s", e)
            return None
