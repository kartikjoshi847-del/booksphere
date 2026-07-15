const express = require("express")
const cors = require("cors")

const app = express()
app.use(cors())
app.use(express.json())

let comments = []

app.post("/addComment", (req, res) => {
    const { bookTitle, comment } = req.body

    comments.push({ bookTitle, comment })

    console.log("Saved:", comment)

    res.json({ status: "ok" })
})

app.get("/getComments", (req, res) => {
    const bookTitle = req.query.bookTitle

    const result = comments.filter(
        c => c.bookTitle.trim().toLowerCase() === bookTitle.trim().toLowerCase()
    )

    res.json(result)
})

app.listen(3000, () => {
    console.log("Server running on http://localhost:3000")
})