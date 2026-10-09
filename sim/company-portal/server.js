const express = require('express');
const multer = require('multer');
const path = require('path');

const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Store in memory
const applications = [];
const invoices = [];
let uploadAttempts = 0;

const upload = multer({ dest: 'uploads/' });

// --- HTML Pages (Agent navigates these) ---

app.get('/', (req, res) => {
    res.send(`
        <!DOCTYPE html>
        <html>
        <head><title>TechCorp Home</title></head>
        <body>
            <h1>Welcome to TechCorp</h1>
            <a href="/careers" id="link-careers">Careers</a>
            <a href="/internal" id="link-internal">Internal Portal</a>
        </body>
        </html>
    `);
});

app.get('/careers', (req, res) => {
    res.send(`
        <!DOCTYPE html>
        <html>
        <head><title>TechCorp Careers</title></head>
        <body>
            <h1>Careers</h1>
            <ul>
                <li>
                    <h2>AI Engineering Intern</h2>
                    <p>Work on cutting-edge AI systems</p>
                    <a href="/apply/ai-intern" id="apply-ai">Apply Now</a>
                </li>
                <li>
                    <h2>Frontend Developer</h2>
                    <p>Build beautiful UIs</p>
                    <a href="/apply/frontend-dev" id="apply-frontend">Apply Now</a>
                </li>
            </ul>
        </body>
        </html>
    `);
});

app.get('/apply/:jobId', (req, res) => {
    res.send(`
        <!DOCTYPE html>
        <html>
        <head><title>Apply - ${req.params.jobId}</title></head>
        <body>
            <h1>Apply for ${req.params.jobId}</h1>
            <form action="/api/applications" method="POST" enctype="multipart/form-data">
                <input type="hidden" name="jobId" value="${req.params.jobId}">
                
                <label>Full Name *</label>
                <input type="text" name="name" id="name" required><br><br>
                
                <label>Email *</label>
                <input type="email" name="email" id="email" required><br><br>
                
                <label>Phone</label>
                <input type="tel" name="phone" id="phone"><br><br>
                
                <label>University *</label>
                <input type="text" name="university" id="university" required><br><br>
                
                <label>Degree</label>
                <select name="degree" id="degree">
                    <option value="">Select...</option>
                    <option value="BS">Bachelors</option>
                    <option value="MS">Masters</option>
                    <option value="PhD">PhD</option>
                </select><br><br>
                
                <label>GPA</label>
                <input type="text" name="gpa" id="gpa"><br><br>
                
                <!-- UNEXPECTED FIELDS -->
                <label>Preferred Start Date</label>
                <input type="date" name="startDate" id="startDate"><br><br>
                
                <label>Why are you interested in this role?</label>
                <textarea name="whyInterested" id="whyInterested" rows="4"></textarea><br><br>
                
                <label>Resume Upload *</label>
                <input type="file" name="resume" id="resume" required><br><br>
                
                <button type="submit" id="submit-btn">Submit Application</button>
            </form>
        </body>
        </html>
    `);
});

app.get('/internal', (req, res) => {
    res.send(`
        <!DOCTYPE html>
        <html>
        <head><title>Internal Portal</title></head>
        <body>
            <h1>Internal Systems</h1>
            <a href="/internal/invoices" id="link-invoices">Invoice Entry</a>
        </body>
        </html>
    `);
});

app.get('/internal/invoices', (req, res) => {
    res.send(`
        <!DOCTYPE html>
        <html>
        <head><title>Invoice Entry</title></head>
        <body>
            <h1>Enter New Invoice</h1>
            <form action="/api/invoices" method="POST">
                <label>Invoice Number *</label>
                <input type="text" name="invoiceNumber" id="inv-num" required><br><br>
                
                <label>Vendor *</label>
                <input type="text" name="vendor" id="inv-vendor" required><br><br>
                
                <label>Amount *</label>
                <input type="number" step="0.01" name="amount" id="inv-amt" required><br><br>
                
                <label>Due Date *</label>
                <input type="date" name="dueDate" id="inv-due" required><br><br>
                
                <button type="submit" id="inv-submit">Save Invoice</button>
            </form>
        </body>
        </html>
    `);
});

app.get('/confirmation/app/:id', (req, res) => {
    res.send(`<h1>Application Submitted Successfully</h1><p>ID: ${req.params.id}</p>`);
});
app.get('/confirmation/inv/:id', (req, res) => {
    res.send(`<h1>Invoice Entered Successfully</h1><p>ID: ${req.params.id}</p>`);
});


// --- APIs (Form targets & Verification) ---

app.post('/api/applications', upload.single('resume'), (req, res) => {
    uploadAttempts++;
    // Simulate a transient error on the first upload attempt
    if (uploadAttempts === 1) {
        return res.status(500).send("<html><body><h1>Error</h1><p>Upload failed, please try again</p></body></html>");
    }
    
    const app = {
        id: 'APP-' + Math.floor(Math.random() * 10000),
        status: 'submitted',
        ...req.body,
        resumeFilename: req.file ? req.file.originalname : null,
        timestamp: new Date()
    };
    applications.push(app);
    res.redirect('/confirmation/app/' + app.id);
});

app.post('/api/invoices', (req, res) => {
    const inv = {
        id: 'SYS-INV-' + Math.floor(Math.random() * 10000),
        status: 'entered',
        ...req.body,
        timestamp: new Date()
    };
    invoices.push(inv);
    res.redirect('/confirmation/inv/' + inv.id);
});

// APIs for verification by the agent's Verifier
app.get('/api/applications', (req, res) => res.json(applications));
app.get('/api/invoices', (req, res) => res.json(invoices));

app.listen(3001, () => console.log('Company portal sim listening on 3001'));
