import asyncio
from telethon import TelegramClient
from telethon.sessions import StringSession

async def main():
    api_id = int(input("Enter your TG_API_ID: ").strip())
    api_hash = input("Enter your TG_API_HASH: ").strip()

    client = TelegramClient(StringSession(), api_id, api_hash)
    await client.start()
    
    print("\nSession string generated successfully!")
    print("Copy the string below and save it as your TG_SESSION_STRING on Render:")
    print("-" * 80)
    print(client.session.save())
    print("-" * 80)
    await client.disconnect()

if __name__ == "__main__":
    asyncio.run(main())
