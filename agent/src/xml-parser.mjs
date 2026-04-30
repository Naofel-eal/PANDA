export class XMLParser {
  constructor(xml) {
    this.xml = xml
  }

  extractText(tagName) {
    const regex = new RegExp(`<${tagName}>([^<]+)</${tagName}>`)
    const match = this.xml.match(regex)
    if (!match) {
      throw new Error(`XML element <${tagName}> not found in STS response`)
    }
    return match[1]
  }
}
