const express = require('express');

const app = express();
app.use(express.json());

// Pre-seeded emails
let emails = [
  {
    id: "msg-001",
    from: "recruiter@techcorp.com",
    to: "candidate@email.com",
    subject: "Exciting AI Engineering Intern Opportunity at TechCorp",
    body: "Hi,\n\nWe came across your profile and think you'd be a great fit for our AI Engineering Intern position. The role involves working on cutting-edge AI systems, including LLM-powered autonomous agents.\n\nYou can apply at: http://localhost:3001/apply/ai-intern\n\nLooking forward to your application!\n\nBest,\nSarah Chen\nTalent Acquisition, TechCorp",
    date: "2024-10-01T10:30:00Z",
    folder: "INBOX",
    read: false
  },
  {
    id: "msg-002",
    from: "finance@techcorp.com",
    to: "candidate@email.com",
    subject: "Invoice #INV-2024-0847 - Payment Due",
    body: "Hi,\n\nPlease find attached Invoice #INV-2024-0847.\n\nVendor: CloudServices Inc.\nAmount: $4,250.00\nDue Date: October 15, 2024\n\nPlease enter this into the internal system at: http://localhost:3001/internal/invoices\n\nThanks,\nFinance Team",
    date: "2024-10-02T14:15:00Z",
    folder: "INBOX",
    read: false,
    attachments: ["invoice-INV-2024-0847.pdf"]
  },
  {
    id: "msg-003",
    from: "manager@techcorp.com",
    to: "candidate@email.com",
    subject: "Please forward the latest invoice to accounting",
    body: "Hi,\n\nCould you please find the latest invoice from CloudServices Inc. in your email, extract the key details (invoice number, amount, due date), and email them to accounting@techcorp.com?\n\nThanks,\nJohn",
    date: "2024-10-03T09:00:00Z",
    folder: "INBOX",
    read: false
  }
];

// --- APIs ---

app.get('/api/emails/search', (req, res) => {
    const { q, from, folder } = req.query;
    let results = emails;
    
    if (folder) results = results.filter(e => e.folder === folder);
    if (from) results = results.filter(e => e.from.toLowerCase().includes(from.toLowerCase()));
    if (q) {
        const query = q.toLowerCase();
        results = results.filter(e => 
            e.subject.toLowerCase().includes(query) || 
            e.body.toLowerCase().includes(query)
        );
    }
    
    // Return summaries (no full body)
    res.json(results.map(e => ({
        id: e.id,
        from: e.from,
        to: e.to,
        subject: e.subject,
        date: e.date,
        folder: e.folder,
        read: e.read,
        snippet: e.body.substring(0, 100) + '...'
    })));
});

app.get('/api/emails/:id', (req, res) => {
    const email = emails.find(e => e.id === req.params.id);
    if (!email) return res.status(404).json({error: 'Not found'});
    email.read = true;
    res.json(email);
});

app.post('/api/emails/draft', (req, res) => {
    const draft = {
        id: 'draft-' + Math.floor(Math.random() * 10000),
        from: 'candidate@email.com',
        to: req.body.to,
        subject: req.body.subject,
        body: req.body.body,
        date: new Date().toISOString(),
        folder: 'DRAFTS',
        status: 'DRAFT',
        read: true
    };
    emails.push(draft);
    res.json(draft);
});

app.post('/api/emails/:id/send', (req, res) => {
    const email = emails.find(e => e.id === req.params.id);
    if (!email) return res.status(404).json({error: 'Not found'});
    
    email.folder = 'SENT';
    email.status = 'SENT';
    email.date = new Date().toISOString();
    
    res.json({ id: email.id, status: 'SENT' });
});

app.get('/api/emails/:id/status', (req, res) => {
    const email = emails.find(e => e.id === req.params.id);
    if (!email) return res.status(404).json({error: 'Not found'});
    res.json({ id: email.id, status: email.status || 'RECEIVED' });
});

app.get('/api/emails', (req, res) => {
    res.json(emails);
});

app.listen(3002, () => console.log('Mock mail sim listening on 3002'));
