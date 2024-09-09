import { ChatOpenAI } from "@langchain/openai";

async function main() {
  const chatModel = new ChatOpenAI({});

  const response = await chatModel.invoke("What is LangSmith?");
  console.log(response);
}

main().catch(console.error);